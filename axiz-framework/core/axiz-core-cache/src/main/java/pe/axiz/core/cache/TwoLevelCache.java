package pe.axiz.core.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caché L1 (local) + L2 (Redis). Las lecturas van a L1 y luego a L2; las escrituras y los desalojos
 * van a ambos. Un L2 que falla nunca hace fallar a quien llama: el error se registra en log y la
 * llamada continúa como un fallo de caché (se usa la fuente de verdad).
 *
 * <p>Frescura: una copia local nunca sobrevive a la entrada de la que se tomó. Vive el tiempo que
 * L2 informó que le quedaba a la entrada, menos la duración completa de esa lectura, limitado por
 * el límite de L1. La lectura se mide con el reloj monotónico y con el reloj con el que L1 expira
 * las entradas ({@link ByteCache#ticks()}), y cuenta la más larga de las dos, de modo que una
 * respuesta lenta no puede estirar la expiración en ninguna de las dos bases de tiempo. Una
 * respuesta que llega sin tiempo restante se devuelve pero no se copia. Una entrada de L2 sin
 * expiración se copia por el límite de L1; un nivel que no puede saber la expiración no recibe
 * copia local alguna. Lo que L1 aún puede servir tras un cambio en otro lugar está acotado por el
 * límite de L1: un desalojo en otra instancia llega a este L1 solo mediante el desalojo de la misma
 * clave aquí, o cuando la copia expira.
 */
public final class TwoLevelCache implements ByteCache {

  private static final Logger LOG = LoggerFactory.getLogger(TwoLevelCache.class);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ByteCache is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final ByteCache l1;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ByteCache is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final ByteCache l2;

  private final Duration l1MaxTtl;
  private final LongSupplier nanoTime;

  public TwoLevelCache(ByteCache l1, ByteCache l2, Duration l1MaxTtl) {
    this(l1, l2, l1MaxTtl, System::nanoTime);
  }

  /**
   * Con el reloj monotónico que mide las lecturas de L2, en nanosegundos ({@code System::nanoTime}
   * en producción; uno controlado en pruebas).
   */
  public TwoLevelCache(ByteCache l1, ByteCache l2, Duration l1MaxTtl, LongSupplier nanoTime) {
    this.l1 = Objects.requireNonNull(l1);
    this.l2 = Objects.requireNonNull(l2);
    this.l1MaxTtl = Objects.requireNonNull(l1MaxTtl);
    this.nanoTime = Objects.requireNonNull(nanoTime);
  }

  @Override
  public Optional<byte[]> get(String key) {
    Optional<byte[]> local = l1.get(key);
    if (local.isPresent()) {
      return local;
    }
    try {
      long started = nanoTime.getAsLong();
      long localStarted = l1.ticks();
      Optional<CachedValue> remote = l2.getWithTtl(key);
      if (remote.isEmpty()) {
        return Optional.empty();
      }
      long took = Math.max(nanoTime.getAsLong() - started, l1.ticks() - localStarted);
      Duration readTook = Duration.ofNanos(Math.max(0, took));
      Duration forL1 = localTtl(remote.get(), readTook);
      if (forL1.isPositive()) {
        l1.put(key, remote.get().value(), forL1);
      }
      return Optional.of(remote.get().value());
    } catch (RuntimeException e) {
      LOG.warn(
          "L2 cache read failed for a key; continuing without cache: {}",
          e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Tiempo que puede vivir la copia local: el tiempo que le quedaba a L2 menos la duración de la
   * lectura (L2 lo midió en algún punto de esa lectura, así que se descuenta la lectura completa),
   * limitado por el límite de L1; cero cuando se desconoce la expiración.
   */
  private Duration localTtl(CachedValue remote, Duration readTook) {
    if (!remote.expiryKnown()) {
      return Duration.ZERO;
    }
    if (remote.neverExpires()) {
      return l1MaxTtl;
    }
    return capped(remote.remaining().orElse(Duration.ZERO).minus(readTook));
  }

  private Duration capped(Duration remaining) {
    return remaining.compareTo(l1MaxTtl) < 0 ? remaining : l1MaxTtl;
  }

  /** Las copias locales expiran según el reloj de L1. */
  @Override
  public long ticks() {
    return l1.ticks();
  }

  @Override
  public void put(String key, byte[] value, Duration ttl) {
    l1.put(key, value, capped(ttl));
    try {
      l2.put(key, value, ttl);
    } catch (RuntimeException e) {
      LOG.warn("L2 cache write failed; value kept only locally: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public void evict(String key) {
    l1.evict(key);
    try {
      l2.evict(key);
    } catch (RuntimeException e) {
      LOG.warn("L2 cache eviction failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public long evictByPrefix(String prefix) {
    long n = l1.evictByPrefix(prefix);
    try {
      n += l2.evictByPrefix(prefix);
    } catch (RuntimeException e) {
      LOG.warn("L2 cache prefix eviction failed: {}", e.getClass().getSimpleName());
    }
    return n;
  }
}

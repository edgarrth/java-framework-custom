package pe.axiz.core.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Almacén de nonces para una sola instancia (pruebas, ejecuciones locales, herramientas de una
 * réplica). Acotado: las entradas expiradas se barren cuando se alcanza el límite y, si sigue
 * lleno, los nonces nuevos se rechazan lanzando una excepción, de modo que el verificador falla en
 * cerrado en lugar de olvidar nonces aún válidos.
 */
public final class InMemoryNonceStore implements NonceStore {

  private final Map<String, Instant> used = new ConcurrentHashMap<>();
  private final int maxEntries;
  private final Clock clock;

  public InMemoryNonceStore(int maxEntries, Clock clock) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be positive");
    }
    this.maxEntries = maxEntries;
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public boolean markUsed(String nonce, Duration ttl) {
    Instant now = clock.instant();
    if (used.size() >= maxEntries) {
      used.values().removeIf(expiry -> !expiry.isAfter(now));
      if (used.size() >= maxEntries) {
        throw new IllegalStateException("Nonce store is full");
      }
    }
    AtomicBoolean first = new AtomicBoolean();
    used.compute(
        nonce,
        (k, expiry) -> {
          if (expiry != null && expiry.isAfter(now)) {
            return expiry;
          }
          first.set(true);
          return now.plus(ttl);
        });
    return first.get();
  }
}

package pe.axiz.core.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.id.IdempotencyKeys;
import pe.axiz.core.lang.Strings;

/**
 * Idempotencia de API: se ejecuta la primera petición con una clave, una petición idéntica repetida
 * recibe la respuesta almacenada, una concurrente recibe 409 y la misma clave con una petición
 * distinta recibe 422.
 *
 * <p>Cada ejecución recibe un token de propietario en {@link Proceed}; {@link #complete} y {@link
 * #fail} solo actúan mientras esa ejecución conserva la clave. Cuando el lock expira y otra
 * petición toma la clave, una finalización tardía de la primera se descarta en lugar de
 * sobrescribir el resultado de la segunda.
 */
public final class IdempotencyService {

  private final IdempotencyStore store;
  private final Duration inProgressTtl;
  private final Duration retention;
  private final Clock clock;
  private final @Nullable IdempotencyResolver resolver;

  public IdempotencyService(
      IdempotencyStore store, Duration inProgressTtl, Duration retention, Clock clock) {
    this(store, inProgressTtl, retention, clock, null);
  }

  public IdempotencyService(
      IdempotencyStore store,
      Duration inProgressTtl,
      Duration retention,
      Clock clock,
      @Nullable IdempotencyResolver resolver) {
    this.store = Objects.requireNonNull(store);
    this.inProgressTtl = Objects.requireNonNull(inProgressTtl);
    this.retention = Objects.requireNonNull(retention);
    this.clock = Objects.requireNonNull(clock);
    this.resolver = resolver;
    if (inProgressTtl.compareTo(Duration.ofMillis(3)) < 0) {
      throw new IllegalArgumentException("inProgressTtl must be at least 3 milliseconds");
    }
    if (retention.compareTo(inProgressTtl) <= 0)
      throw new IllegalArgumentException("retention must exceed the execution lease");
  }

  /** Decisión para una petición entrante. */
  public sealed interface Decision permits Proceed, Replay {}

  /**
   * Se ejecuta la operación y luego se llama a {@link IdempotencyService#complete} o a {@link
   * IdempotencyService#fail} con este valor.
   *
   * @param key clave con alcance
   * @param owner token de esta ejecución
   */
  public record Proceed(String key, String owner) implements Decision {
    public Proceed {
      Objects.requireNonNull(key);
      Objects.requireNonNull(owner);
    }
  }

  /**
   * Se devuelve la respuesta almacenada.
   *
   * @param response respuesta almacenada
   */
  public record Replay(StoredResponse response) implements Decision {}

  /**
   * Inicia el procesamiento.
   *
   * @param scope alcance de operación y llamador (las claves son únicas por alcance), ver {@link
   *     #scope}
   * @param idempotencyKey valor de la cabecera Idempotency-Key
   * @param fingerprint huella de la petición, ver {@link #fingerprint}
   * @return la decisión
   * @throws BusinessException con errores 400, 409 o 422
   */
  public Decision begin(String scope, @Nullable String idempotencyKey, String fingerprint) {
    String key = scopedKey(scope, idempotencyKey);
    Instant now = clock.instant();
    Optional<Proceed> started = tryStart(key, fingerprint, now);
    if (started.isPresent()) {
      return started.get();
    }
    Optional<IdempotencyRecord> existing = store.find(key, now);
    if (existing.isEmpty()) {
      throw new BusinessException(
          IdempotencyErrors.OUTCOME_UNKNOWN, "operation changed during acquisition");
    }
    IdempotencyRecord record = existing.get();
    requireFingerprint(record, fingerprint);
    IdempotencyResolver reconciliation = resolver;
    if (uncertain(record, now) && reconciliation != null) {
      Optional<Resolution> resolution = reconciliation.resolve(key, record);
      if (resolution.isPresent()) {
        boolean changed = store.resolve(record, resolution.get(), now, now.plus(retention));
        if (changed && resolution.get().response() == null)
          throw new BusinessException(
              IdempotencyErrors.RESOLVED_WITHOUT_EFFECT,
              "operation reconciled without effect; repeat the request with the same key");
        record =
            store
                .find(key, now)
                .orElseThrow(
                    () ->
                        new BusinessException(
                            IdempotencyErrors.OUTCOME_UNKNOWN, "concurrent reconciliation"));
      }
    }
    return existingDecision(record, fingerprint, now);
  }

  static String scopedKey(String scope, @Nullable String idempotencyKey) {
    if (!IdempotencyKeys.isValid(idempotencyKey)) {
      throw new BusinessException(
          IdempotencyErrors.KEY_REQUIRED, "missing or invalid Idempotency-Key");
    }
    return scope + ":" + idempotencyKey;
  }

  static void requireFingerprint(IdempotencyRecord r, String fingerprint) {
    if (!r.fingerprint().equals(fingerprint)) {
      throw new BusinessException(
          IdempotencyErrors.KEY_REUSED, "key reused with a different request");
    }
  }

  static boolean uncertain(IdempotencyRecord r, Instant now) {
    return r.status() == IdempotencyRecord.Status.UNKNOWN
        || (r.status() == IdempotencyRecord.Status.IN_PROGRESS && !r.leaseUntil().isAfter(now));
  }

  static Decision existingDecision(IdempotencyRecord r, String fingerprint, Instant now) {
    requireFingerprint(r, fingerprint);
    StoredResponse response = r.response();
    if (r.status() == IdempotencyRecord.Status.COMPLETED && response != null) {
      return new Replay(response);
    }
    if (uncertain(r, now))
      throw new BusinessException(
          IdempotencyErrors.OUTCOME_UNKNOWN,
          "operation outcome uncertain; consult the operation status");
    throw new BusinessException(IdempotencyErrors.IN_PROGRESS, "request in progress", Map.of());
  }

  private Optional<Proceed> tryStart(String key, String fingerprint, Instant now) {
    String owner = UUID.randomUUID().toString();
    boolean started =
        store.tryStart(
            IdempotencyRecord.builder(
                    key, fingerprint, now.plus(inProgressTtl), now.plus(retention))
                .status(IdempotencyRecord.Status.IN_PROGRESS)
                .owner(owner)
                .response(null)
                .resolutionVersion(0)
                .failure(null)
                .build());
    return started ? Optional.of(new Proceed(key, owner)) : Optional.empty();
  }

  /**
   * Almacena la respuesta para replay.
   *
   * @return {@code false} cuando esta ejecución perdió la clave (su retención expiró y otra
   *     petición adquirió la clave, o una reconciliación cambió su estado); en ese caso la
   *     respuesta no se almacena
   */
  public boolean complete(Proceed execution, StoredResponse response) {
    return store.complete(
        execution.key(), execution.owner(), response, clock.instant().plus(retention));
  }

  /**
   * Marca el resultado como UNKNOWN tras un fallo; los errores de servidor no se reproducen. Una
   * petición posterior recibe un conflicto hasta que un {@link IdempotencyResolver} reconcilia el
   * efecto. {@link #releaseWithoutEffect(Proceed)} se usa solo cuando se sabe que no hubo ningún
   * efecto de negocio, para que un reintento no pueda duplicar una operación con commit.
   *
   * @return {@code false} cuando esta ejecución no conserva la clave
   */
  public boolean fail(Proceed execution) {
    return markUnknown(execution, "execution-failed");
  }

  public boolean markUnknown(Proceed execution, String diagnostic) {
    return store.markUnknown(execution.key(), execution.owner(), diagnostic, clock.instant());
  }

  public boolean releaseWithoutEffect(Proceed execution) {
    return store.releaseWithoutEffect(execution.key(), execution.owner());
  }

  public boolean renew(Proceed execution) {
    Instant now = clock.instant();
    Instant leaseUntil = now.plus(inProgressTtl);
    return store.renew(
        execution.key(), execution.owner(), leaseUntil, leaseUntil.plus(retention), now);
  }

  public IdempotencyLease startLease(Proceed execution) {
    return IdempotencyLease.start(
        inProgressTtl.dividedBy(3),
        () -> CompletableFuture.completedFuture(renew(execution)),
        () -> CompletableFuture.completedFuture(markUnknown(execution, "lease-lost")));
  }

  /**
   * Alcance de una clave: la operación más un hash de quién la llama. Dos llamadores que comparten
   * un Idempotency-Key nunca ven las respuestas del otro, incluso entre emisores o tenants
   * distintos.
   *
   * <p>Cada parte se incluye en el hash como un marcador ({@code 01} ausente, {@code 02} presente),
   * sus bytes UTF-8 y un separador {@code 00}. La codificación es inequívoca porque una parte no
   * puede contener caracteres de control: UTF-8 nunca codifica otro carácter con un byte menor que
   * {@code 0x20}, de modo que ninguna parte puede imitar un separador o un marcador. Una parte con
   * un carácter de control (U+0000 a U+001F, U+007F) se rechaza antes de que empiece cualquier
   * operación; el mapeo de tokens de axiz ya rechaza esos claims, y las integraciones que
   * construyen alcances por otros medios reciben la misma verificación aquí. También se rechazan
   * los surrogates UTF-16 sin pareja: UTF-8 los reemplazaría y fusionaría identidades distintas.
   * Las identidades válidas conservan sus hashes originales, también entre versiones y en
   * reintentos en curso.
   *
   * @param operation nombre estable de la operación (por ejemplo {@code
   *     com.acme.PaymentController#create})
   * @param issuer emisor del token, si existe
   * @param tenant tenant, si existe
   * @param client aplicación que llama, si existe
   * @param subject usuario final, si existe
   * @return {@code operation:hash}
   * @throws IllegalArgumentException cuando una parte contiene un carácter de control o Unicode mal
   *     formado
   */
  public static String scope(
      String operation,
      @Nullable String issuer,
      @Nullable String tenant,
      @Nullable String client,
      @Nullable String subject) {
    MessageDigest d = sha256();
    for (String part : Arrays.asList(issuer, tenant, client, subject)) {
      if (part == null) {
        d.update((byte) 1);
      } else {
        requireNoControlCharacters(part);
        d.update((byte) 2);
        d.update(part.getBytes(StandardCharsets.UTF_8));
      }
      d.update((byte) 0);
    }
    return operation + ":" + HexFormat.of().formatHex(d.digest());
  }

  private static void requireNoControlCharacters(String part) {
    if (!Strings.isWellFormedUnicode(part)) {
      throw new IllegalArgumentException("An idempotency scope part contains malformed Unicode");
    }
    for (int i = 0; i < part.length(); i++) {
      char c = part.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw new IllegalArgumentException(
            "An idempotency scope part contains a control character (U+"
                + String.format("%04X", (int) c)
                + ")");
      }
    }
  }

  /** SHA-256 sobre método, ruta y cuerpo (sin query string). */
  public static String fingerprint(String method, String path, byte[] body) {
    return fingerprint(method, path, null, body);
  }

  /**
   * SHA-256 sobre método, ruta, query string y cuerpo. La query string se usa exactamente como se
   * envió: reordenar parámetros es una petición distinta (un parámetro repetido como {@code
   * sort=a&sort=b} puede depender del orden), de modo que un cliente debe reenviar la misma
   * petición para reutilizar una clave.
   *
   * @param method método HTTP (o una etiqueta fija para comandos no HTTP)
   * @param path ruta de la petición
   * @param query query string en bruto sin {@code ?}, si existe
   * @param body cuerpo de la petición
   * @return digest hexadecimal
   */
  public static String fingerprint(
      String method, String path, @Nullable String query, byte[] body) {
    MessageDigest d = sha256();
    d.update(method.getBytes(StandardCharsets.UTF_8));
    d.update((byte) 0);
    d.update(path.getBytes(StandardCharsets.UTF_8));
    d.update((byte) 0);
    d.update((query == null ? "" : query).getBytes(StandardCharsets.UTF_8));
    d.update((byte) 0);
    d.update(body);
    return HexFormat.of().formatHex(d.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

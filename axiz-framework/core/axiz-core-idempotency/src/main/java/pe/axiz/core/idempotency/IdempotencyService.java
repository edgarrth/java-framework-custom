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
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.id.IdempotencyKeys;

/**
 * API idempotency: the first request with a key runs, a repeated identical request gets the stored
 * response, a concurrent one gets 409 and the same key with a different request gets 422.
 *
 * <p>Each execution receives an owner token in {@link Proceed}; {@link #complete} and {@link #fail}
 * only act while that execution still holds the key. When the lock expires and another request
 * takes the key, a late completion of the first one is discarded instead of overwriting the result
 * of the second.
 */
public final class IdempotencyService {

  private final IdempotencyStore store;
  private final Duration inProgressTtl;
  private final Duration retention;
  private final Clock clock;

  public IdempotencyService(
      IdempotencyStore store, Duration inProgressTtl, Duration retention, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.inProgressTtl = Objects.requireNonNull(inProgressTtl);
    this.retention = Objects.requireNonNull(retention);
    this.clock = Objects.requireNonNull(clock);
    if (inProgressTtl.isNegative() || inProgressTtl.isZero()) {
      throw new IllegalArgumentException("inProgressTtl must be positive");
    }
  }

  /** Decision for an incoming request. */
  public sealed interface Decision permits Proceed, Replay {}

  /**
   * Run the operation, then call {@link IdempotencyService#complete} or {@link
   * IdempotencyService#fail} with this value.
   *
   * @param key scoped key
   * @param owner token of this execution
   */
  public record Proceed(String key, String owner) implements Decision {
    public Proceed {
      Objects.requireNonNull(key);
      Objects.requireNonNull(owner);
    }
  }

  /**
   * Return the stored response.
   *
   * @param response stored response
   */
  public record Replay(StoredResponse response) implements Decision {}

  /**
   * Starts processing.
   *
   * @param scope operation and caller scope (keys are unique per scope), see {@link #scope}
   * @param idempotencyKey value of the Idempotency-Key header
   * @param fingerprint request fingerprint, see {@link #fingerprint}
   * @return the decision
   * @throws BusinessException with 400, 409 or 422 errors
   */
  public Decision begin(String scope, @Nullable String idempotencyKey, String fingerprint) {
    if (!IdempotencyKeys.isValid(idempotencyKey)) {
      throw new BusinessException(
          IdempotencyErrors.KEY_REQUIRED, "missing or invalid Idempotency-Key");
    }
    String key = scope + ":" + idempotencyKey;
    Instant now = clock.instant();
    Optional<Proceed> started = tryStart(key, fingerprint, now);
    if (started.isPresent()) {
      return started.get();
    }
    Optional<IdempotencyRecord> existing = store.find(key, now);
    if (existing.isEmpty()) {
      // expired between both calls: try once more
      return tryStart(key, fingerprint, now)
          .<Decision>map(p -> p)
          .orElseThrow(
              () -> new BusinessException(IdempotencyErrors.IN_PROGRESS, "concurrent request"));
    }
    IdempotencyRecord r = existing.get();
    if (!r.fingerprint().equals(fingerprint)) {
      throw new BusinessException(
          IdempotencyErrors.KEY_REUSED, "key reused with a different request");
    }
    StoredResponse response = r.response();
    if (r.status() == IdempotencyRecord.Status.COMPLETED && response != null) {
      return new Replay(response);
    }
    throw new BusinessException(IdempotencyErrors.IN_PROGRESS, "request in progress", Map.of());
  }

  private Optional<Proceed> tryStart(String key, String fingerprint, Instant now) {
    String owner = UUID.randomUUID().toString();
    boolean started =
        store.tryStart(
            new IdempotencyRecord(
                key,
                fingerprint,
                IdempotencyRecord.Status.IN_PROGRESS,
                owner,
                null,
                now.plus(inProgressTtl)));
    return started ? Optional.of(new Proceed(key, owner)) : Optional.empty();
  }

  /**
   * Stores the response for replay.
   *
   * @return {@code false} when this execution no longer holds the key (its lock expired and the key
   *     was released or taken by another request); the response is then not stored
   */
  public boolean complete(Proceed execution, StoredResponse response) {
    return store.complete(
        execution.key(), execution.owner(), response, clock.instant().plus(retention));
  }

  /**
   * Releases the key after a failure; server errors must not be replayed.
   *
   * @return {@code false} when this execution no longer holds the key
   */
  public boolean fail(Proceed execution) {
    return store.release(execution.key(), execution.owner());
  }

  /**
   * Scope of a key: the operation plus a hash of who calls it. Two callers that share an
   * Idempotency-Key never see each other's responses, even across issuers or tenants.
   *
   * @param operation stable operation name (for example {@code com.acme.PaymentController#create})
   * @param issuer token issuer, if any
   * @param tenant tenant, if any
   * @param client calling application, if any
   * @param subject end user, if any
   * @return {@code operation:hash}
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
        d.update((byte) 2);
        d.update(part.getBytes(StandardCharsets.UTF_8));
      }
      d.update((byte) 0);
    }
    return operation + ":" + HexFormat.of().formatHex(d.digest());
  }

  /** SHA-256 over method, path and body (no query string). */
  public static String fingerprint(String method, String path, byte[] body) {
    return fingerprint(method, path, null, body);
  }

  /**
   * SHA-256 over method, path, query string and body. The query string is used exactly as sent:
   * reordering parameters is a different request (a repeated parameter such as {@code
   * sort=a&sort=b} may be order-sensitive), so a client must resend the same request to reuse a
   * key.
   *
   * @param method HTTP method (or a fixed label for non-HTTP commands)
   * @param path request path
   * @param query raw query string without {@code ?}, if any
   * @param body request body
   * @return hex digest
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

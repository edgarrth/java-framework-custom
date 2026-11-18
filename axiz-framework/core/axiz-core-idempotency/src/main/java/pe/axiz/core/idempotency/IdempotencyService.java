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
    if (inProgressTtl.isNegative() || inProgressTtl.isZero()) {
      throw new IllegalArgumentException("inProgressTtl must be positive");
    }
    if (retention.compareTo(inProgressTtl) <= 0)
      throw new IllegalArgumentException("retention must exceed the execution lease");
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
   * Stores the response for replay.
   *
   * @return {@code false} when this execution no longer holds the key (its retention expired and
   *     the key was acquired by another request, or reconciliation changed its state); the response
   *     is then not stored
   */
  public boolean complete(Proceed execution, StoredResponse response) {
    return store.complete(
        execution.key(), execution.owner(), response, clock.instant().plus(retention));
  }

  /**
   * Marks the outcome UNKNOWN after a failure; server errors are not replayed. A subsequent request
   * receives a conflict until an {@link IdempotencyResolver} reconciles the effect. Use {@link
   * #releaseWithoutEffect(Proceed)} only when the absence of any business effect is known, so a
   * retry cannot duplicate a committed operation.
   *
   * @return {@code false} when this execution no longer holds the key
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
   * Scope of a key: the operation plus a hash of who calls it. Two callers that share an
   * Idempotency-Key never see each other's responses, even across issuers or tenants.
   *
   * <p>Each part is hashed as a marker ({@code 01} absent, {@code 02} present), its UTF-8 bytes and
   * a {@code 00} separator. The encoding is unambiguous because a part may not contain control
   * characters: UTF-8 never encodes another character with a byte below {@code 0x20}, so no part
   * can imitate a separator or a marker. A part with a control character (U+0000 to U+001F, U+007F)
   * is rejected before any operation starts; the axiz token mapping already refuses such claims,
   * and integrations that build scopes by other means get the same check here. Unpaired UTF-16
   * surrogates are also rejected: UTF-8 would replace them and merge distinct identities. Valid
   * identities retain their original hashes, including across upgrades and in-flight retries.
   *
   * @param operation stable operation name (for example {@code com.acme.PaymentController#create})
   * @param issuer token issuer, if any
   * @param tenant tenant, if any
   * @param client calling application, if any
   * @param subject end user, if any
   * @return {@code operation:hash}
   * @throws IllegalArgumentException when a part contains a control character or malformed Unicode
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

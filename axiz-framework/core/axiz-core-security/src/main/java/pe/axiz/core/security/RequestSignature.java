package pe.axiz.core.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * HMAC-SHA256 signatures for partner callbacks and webhooks, with key rotation and replay
 * protection.
 *
 * <p>Signed string (format {@value #VERSION}), one field per line:
 *
 * <pre>
 * AXIZ-HMAC-V2
 * &lt;key id&gt;
 * &lt;epoch seconds&gt;
 * &lt;nonce&gt;
 * &lt;METHOD&gt;
 * &lt;request target: raw path and query, exactly as sent&gt;
 * &lt;lowercase hex SHA-256 of the body bytes&gt;
 * </pre>
 *
 * <p>The format is unambiguous: no field may contain a line break (each one is validated against a
 * strict alphabet) and the body is represented by its digest, so two different requests never
 * produce the same signed string. The request target is signed as raw bytes on the wire (path plus
 * {@code ?query}, percent-encoded): it is not normalized, so a proxy that rewrites it (reorders the
 * query, decodes characters) breaks the signature, which is the safe outcome. An empty body and a
 * missing body are the same thing (zero bytes).
 *
 * <p>{@link #verify} checks, in this order: format, time window, key, signature (constant time) and
 * finally the nonce, which the {@link NonceStore} accepts only once while the signature could still
 * be valid. Only authentic messages consume nonces, so a forger cannot burn them. A store that
 * cannot answer makes the verification fail closed ({@link Outcome#REPLAY_CHECK_UNAVAILABLE}).
 *
 * <p>Headers: {@value #SIGNATURE_HEADER} ({@code keyId=<id>,v2=<hex>}), {@value #TIMESTAMP_HEADER}
 * (epoch seconds) and {@value #NONCE_HEADER}.
 */
public final class RequestSignature {

  /** Version of the signed string; part of what is signed. */
  public static final String VERSION = "AXIZ-HMAC-V2";

  public static final String SIGNATURE_HEADER = "X-Axiz-Signature";
  public static final String TIMESTAMP_HEADER = "X-Axiz-Timestamp";
  public static final String NONCE_HEADER = "X-Axiz-Nonce";

  /** Longest request target accepted (bytes). */
  public static final int MAX_TARGET = 8192;

  private static final String ALGORITHM = "HmacSHA256";
  private static final int MIN_KEY_BYTES = 32;
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
  private static final Pattern METHOD = Pattern.compile("[A-Z]{1,16}");
  private static final Pattern HEADER =
      Pattern.compile("keyId=([A-Za-z0-9._-]{1,64}),v2=([0-9a-f]{64})");
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Result of a verification; only {@link #VALID} accepts the request. */
  public enum Outcome {
    VALID,
    /** A field or the signature header is not in the expected format. */
    MALFORMED,
    /** The timestamp is outside the tolerance window. */
    STALE,
    /** The key id is not one of the configured keys. */
    UNKNOWN_KEY,
    BAD_SIGNATURE,
    /** An authentic message whose nonce was already used. */
    REPLAYED,
    /** The nonce store could not answer: rejected (fail closed). */
    REPLAY_CHECK_UNAVAILABLE;

    public boolean valid() {
      return this == VALID;
    }
  }

  /** What is signed. Immutable: the body is copied on the way in and out. */
  public static final class Message {
    private final Instant timestamp;
    private final String nonce;
    private final String method;
    private final String target;
    private final byte[] body;

    /**
     * Creates a message.
     *
     * @param timestamp when it was signed (seconds precision)
     * @param nonce unique value per message, 16 to 128 characters of {@code [A-Za-z0-9_-]}
     * @param method upper-case HTTP method
     * @param target raw request target (path and optional query) exactly as sent, starting with
     *     {@code /}; only printable ASCII (non-ASCII characters must be percent-encoded)
     * @param body body bytes (empty when there is none)
     */
    public Message(Instant timestamp, String nonce, String method, String target, byte[] body) {
      this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
      this.nonce = Objects.requireNonNull(nonce, "nonce");
      this.method = Objects.requireNonNull(method, "method");
      this.target = Objects.requireNonNull(target, "target");
      this.body = Objects.requireNonNull(body, "body").clone();
    }

    /** A message with a UTF-8 body. */
    public static Message of(
        Instant timestamp, String nonce, String method, String target, String body) {
      return new Message(timestamp, nonce, method, target, body.getBytes(StandardCharsets.UTF_8));
    }

    public Instant timestamp() {
      return timestamp;
    }

    public String nonce() {
      return nonce;
    }

    public String method() {
      return method;
    }

    public String target() {
      return target;
    }

    /** A copy of the body. */
    public byte[] body() {
      return body.clone();
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof Message m
          && timestamp.equals(m.timestamp)
          && nonce.equals(m.nonce)
          && method.equals(m.method)
          && target.equals(m.target)
          && Arrays.equals(body, m.body);
    }

    @Override
    public int hashCode() {
      return Objects.hash(timestamp, nonce, method, target, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
      return "Message["
          + method
          + " "
          + target
          + ", nonce="
          + nonce
          + ", "
          + body.length
          + " bytes]";
    }
  }

  private final Map<String, byte[]> keys;
  private final String activeKeyId;
  private final Duration tolerance;
  private final Clock clock;
  private final NonceStore nonces;

  /**
   * Creates a signer and verifier.
   *
   * @param keys signing keys by id (at least 256 bits each); keep the previous key during a
   *     rotation so messages signed with it still verify
   * @param activeKeyId key used by {@link #sign}
   * @param tolerance accepted clock difference in either direction (positive, at most 15 minutes)
   * @param nonces where used nonces are remembered; shared by every instance of the receiver
   */
  public RequestSignature(
      Map<String, byte[]> keys,
      String activeKeyId,
      Duration tolerance,
      Clock clock,
      NonceStore nonces) {
    Map<String, byte[]> copy = new LinkedHashMap<>();
    keys.forEach(
        (id, key) -> {
          if (!KEY_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid signature key id: " + id);
          }
          if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                "Signature key " + id + " must have at least 256 bits");
          }
          copy.put(id, key.clone());
        });
    if (!copy.containsKey(activeKeyId)) {
      throw new IllegalArgumentException("The active signature key " + activeKeyId + " is missing");
    }
    if (tolerance.isNegative()
        || tolerance.isZero()
        || tolerance.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException("Signature tolerance must be between 0 and 15 minutes");
    }
    this.keys = Map.copyOf(copy);
    this.activeKeyId = activeKeyId;
    this.tolerance = tolerance;
    this.clock = Objects.requireNonNull(clock);
    this.nonces = Objects.requireNonNull(nonces);
  }

  /** A fresh random nonce (128 bits, URL-safe). */
  public static String newNonce() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Value of the {@value #SIGNATURE_HEADER} header for the message, with the active key.
   *
   * @throws IllegalArgumentException if a field is not in the expected format
   */
  public String sign(Message message) {
    String problem = problem(message);
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    return "keyId=" + activeKeyId + ",v2=" + hmac(activeKeyId, message);
  }

  /** Checks the message against the value of the {@value #SIGNATURE_HEADER} header. */
  public Outcome verify(Message message, @Nullable String signatureHeader) {
    if (signatureHeader == null || problem(message) != null) {
      return Outcome.MALFORMED;
    }
    var header = HEADER.matcher(signatureHeader);
    if (!header.matches()) {
      return Outcome.MALFORMED;
    }
    Instant now = clock.instant();
    Instant ts = message.timestamp();
    if (ts.isBefore(now.minus(tolerance)) || ts.isAfter(now.plus(tolerance))) {
      return Outcome.STALE;
    }
    String keyId = header.group(1);
    if (!keys.containsKey(keyId)) {
      return Outcome.UNKNOWN_KEY;
    }
    byte[] expected = hmac(keyId, message).getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, header.group(2).getBytes(StandardCharsets.US_ASCII))) {
      return Outcome.BAD_SIGNATURE;
    }
    try {
      // a signature stays acceptable for 2 x tolerance (timestamp anywhere in the window)
      boolean first = nonces.markUsed(keyId + ":" + message.nonce(), tolerance.multipliedBy(2));
      return first ? Outcome.VALID : Outcome.REPLAYED;
    } catch (RuntimeException e) {
      return Outcome.REPLAY_CHECK_UNAVAILABLE;
    }
  }

  /** The exact string that is signed (for interoperability tests with partners). */
  public static String canonical(String keyId, Message message) {
    return VERSION
        + '\n'
        + keyId
        + '\n'
        + message.timestamp().getEpochSecond()
        + '\n'
        + message.nonce()
        + '\n'
        + message.method()
        + '\n'
        + message.target()
        + '\n'
        + HexFormat.of().formatHex(sha256(message.body));
  }

  private static @Nullable String problem(Message m) {
    if (!NONCE.matcher(m.nonce()).matches()) {
      return "The nonce must be 16 to 128 characters of [A-Za-z0-9_-]";
    }
    if (!METHOD.matcher(m.method()).matches()) {
      return "The method must be an upper-case HTTP method";
    }
    String t = m.target();
    if (t.isEmpty() || t.charAt(0) != '/' || t.length() > MAX_TARGET) {
      return "The request target must start with / and have at most " + MAX_TARGET + " characters";
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c < 0x21 || c > 0x7e) {
        return "The request target must be printable ASCII (percent-encode anything else)";
      }
    }
    return null;
  }

  private String hmac(String keyId, Message message) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(keys.get(keyId), ALGORITHM));
      byte[] out = mac.doFinal(canonical(keyId, message).getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC not available", e);
    }
  }

  private static byte[] sha256(byte[] body) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}

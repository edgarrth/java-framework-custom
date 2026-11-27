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
 * Firmas HMAC-SHA256 para callbacks y webhooks de socios, con rotación de claves y protección
 * contra replay.
 *
 * <p>Cadena firmada (formato {@value #VERSION}), un campo por línea:
 *
 * <pre>
 * AXIZ-HMAC-V2
 * &lt;id de clave&gt;
 * &lt;segundos epoch&gt;
 * &lt;nonce&gt;
 * &lt;METHOD&gt;
 * &lt;destino de la petición: ruta y query en bruto, exactamente como se enviaron&gt;
 * &lt;SHA-256 en hexadecimal en minúsculas de los bytes del cuerpo&gt;
 * </pre>
 *
 * <p>El formato es inequívoco: ningún campo puede contener un salto de línea (cada uno se valida
 * contra un alfabeto estricto) y el cuerpo se representa por su digest, de modo que dos peticiones
 * distintas nunca producen la misma cadena firmada. El destino de la petición se firma como los
 * bytes en bruto que viajan por la red (ruta más {@code ?query}, con codificación de porcentaje):
 * no se normaliza, así que un proxy que lo reescribe (reordena la query, decodifica caracteres)
 * rompe la firma, que es el resultado seguro. Un cuerpo vacío y un cuerpo ausente son lo mismo
 * (cero bytes).
 *
 * <p>{@link #verify} verifica, en este orden: formato, ventana de tiempo, clave, firma (en tiempo
 * constante) y por último el nonce, que el {@link NonceStore} acepta una sola vez mientras la firma
 * aún podría ser válida. Solo los mensajes auténticos consumen nonces, de modo que un falsificador
 * no puede agotarlos. Un almacén que no puede responder hace que la verificación falle en cerrado
 * ({@link Outcome#REPLAY_CHECK_UNAVAILABLE}).
 *
 * <p>Cabeceras: {@value #SIGNATURE_HEADER} ({@code keyId=<id>,v2=<hex>}), {@value
 * #TIMESTAMP_HEADER} (segundos epoch) y {@value #NONCE_HEADER}.
 */
public final class RequestSignature {

  /** Versión de la cadena firmada; forma parte de lo que se firma. */
  public static final String VERSION = "AXIZ-HMAC-V2";

  public static final String SIGNATURE_HEADER = "X-Axiz-Signature";
  public static final String TIMESTAMP_HEADER = "X-Axiz-Timestamp";
  public static final String NONCE_HEADER = "X-Axiz-Nonce";

  /** Destino de petición más largo aceptado (bytes). */
  public static final int MAX_TARGET = 8192;

  private static final String ALGORITHM = "HmacSHA256";
  private static final int MIN_KEY_BYTES = 32;
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
  private static final Pattern METHOD = Pattern.compile("[A-Z]{1,16}");
  private static final Pattern HEADER =
      Pattern.compile("keyId=([A-Za-z0-9._-]{1,64}),v2=([0-9a-f]{64})");
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Resultado de una verificación; solo {@link #VALID} acepta la petición. */
  public enum Outcome {
    VALID,
    /** Un campo o la cabecera de firma no tiene el formato esperado. */
    MALFORMED,
    /** La marca de tiempo está fuera de la ventana de tolerancia. */
    STALE,
    /** El id de clave no es una de las claves configuradas. */
    UNKNOWN_KEY,
    BAD_SIGNATURE,
    /** Mensaje auténtico cuyo nonce ya se usó. */
    REPLAYED,
    /** El almacén de nonces no pudo responder: se rechaza (falla en cerrado). */
    REPLAY_CHECK_UNAVAILABLE;

    public boolean valid() {
      return this == VALID;
    }
  }

  /** Lo que se firma. Inmutable: el cuerpo se copia a la entrada y a la salida. */
  public static final class Message {
    private final Instant timestamp;
    private final String nonce;
    private final String method;
    private final String target;
    private final byte[] body;

    /**
     * Crea un mensaje.
     *
     * @param timestamp momento de la firma (precisión de segundos)
     * @param nonce valor único por mensaje, de 16 a 128 caracteres de {@code [A-Za-z0-9_-]}
     * @param method método HTTP en mayúsculas
     * @param target destino de la petición en bruto (ruta y query opcional) exactamente como se
     *     envió, empezando con {@code /}; solo ASCII imprimible (los caracteres no ASCII deben ir
     *     con codificación de porcentaje)
     * @param body bytes del cuerpo (vacío cuando no hay)
     */
    public Message(Instant timestamp, String nonce, String method, String target, byte[] body) {
      this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
      this.nonce = Objects.requireNonNull(nonce, "nonce");
      this.method = Objects.requireNonNull(method, "method");
      this.target = Objects.requireNonNull(target, "target");
      this.body = Objects.requireNonNull(body, "body").clone();
    }

    /** Mensaje con un cuerpo UTF-8. */
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

    /** Copia del cuerpo. */
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
   * Crea un firmante y verificador.
   *
   * @param keys claves de firma por id (al menos 256 bits cada una); durante una rotación se
   *     conserva la clave previa para que los mensajes firmados con ella sigan verificándose
   * @param activeKeyId clave que usa {@link #sign}
   * @param tolerance diferencia de reloj aceptada en cualquier dirección (positiva, como máximo 15
   *     minutos)
   * @param nonces donde se recuerdan los nonces usados; compartido por todas las instancias del
   *     receptor
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

  /** Nonce aleatorio nuevo (128 bits, seguro para URL). */
  public static String newNonce() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Valor de la cabecera {@value #SIGNATURE_HEADER} para el mensaje, con la clave activa.
   *
   * @throws IllegalArgumentException si un campo no tiene el formato esperado
   */
  public String sign(Message message) {
    String problem = problem(message);
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    return "keyId=" + activeKeyId + ",v2=" + hmac(activeKeyId, message);
  }

  /** Verifica el mensaje contra el valor de la cabecera {@value #SIGNATURE_HEADER}. */
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
      // una firma sigue siendo aceptable durante 2 x tolerancia (marca de tiempo en cualquier punto
      // de la ventana)
      boolean first = nonces.markUsed(keyId + ":" + message.nonce(), tolerance.multipliedBy(2));
      return first ? Outcome.VALID : Outcome.REPLAYED;
    } catch (RuntimeException e) {
      return Outcome.REPLAY_CHECK_UNAVAILABLE;
    }
  }

  /** Cadena exacta que se firma (para pruebas de interoperabilidad con socios). */
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

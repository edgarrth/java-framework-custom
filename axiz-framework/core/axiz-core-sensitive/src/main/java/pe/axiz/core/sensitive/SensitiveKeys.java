package pe.axiz.core.sensitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Sensibilidad por nombre de propiedad o de clave JSON, para datos sin anotaciones (mapas, cuerpos
 * HTTP en bruto, cabeceras). La coincidencia no distingue mayúsculas e ignora {@code _} y {@code
 * -}.
 *
 * <p>Un nombre se divide en palabras (camelCase, separadores) y un término sensible puede aparecer
 * en cualquier posición: {@code cvvCode}, {@code pinCode}, {@code passwordConfirmation} y {@code
 * privateKeyPem} son sensibles como {@code cvv} o {@code newPassword}. Un término seguido de una
 * palabra que describe metadatos sobre él ({@code tokenCount}, {@code pinAttempts}, {@code
 * passwordPolicy}, {@code tokenUrl}) no lo es. Cuando coinciden varios términos, decide el más
 * sensible.
 */
public final class SensitiveKeys {

  private static final Map<String, SensitiveField> DEFAULTS =
      Map.ofEntries(
          entry("password", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("passwd", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("passphrase", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("secret", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("clientsecret", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("secretkey", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("privatekey", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("token", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("accesstoken", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("refreshtoken", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("idtoken", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("jwt", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("authorization", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("apikey", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("credential", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("signature", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("pin", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("pinblock", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cvv", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cvv2", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cvc", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cvc2", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cid", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("securitycode", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("codigoseguridad", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("codigodeseguridad", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cardverificationvalue", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("track1", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("track2", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("trackdata", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("otp", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("clave", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("contrasena", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("sessionid", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cookie", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("database64", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("pan", SensitivityLevel.HIGH, MaskType.CARD),
          entry("cardnumber", SensitivityLevel.HIGH, MaskType.CARD),
          entry("primaryaccountnumber", SensitivityLevel.HIGH, MaskType.CARD),
          entry("numerotarjeta", SensitivityLevel.HIGH, MaskType.CARD),
          entry("documentnumber", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("accountnumber", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("cci", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("iban", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("dni", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("ruc", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("expirydate", SensitivityLevel.HIGH, MaskType.FULL),
          entry("expirationdate", SensitivityLevel.HIGH, MaskType.FULL),
          entry("cardexpiry", SensitivityLevel.HIGH, MaskType.FULL),
          entry("expiry", SensitivityLevel.HIGH, MaskType.FULL),
          entry("vencimiento", SensitivityLevel.HIGH, MaskType.FULL),
          entry("fechavencimiento", SensitivityLevel.HIGH, MaskType.FULL),
          entry("fechaexpiracion", SensitivityLevel.HIGH, MaskType.FULL),
          entry("cardholder", SensitivityLevel.HIGH, MaskType.NAME),
          entry("cardholdername", SensitivityLevel.HIGH, MaskType.NAME),
          entry("titulartarjeta", SensitivityLevel.HIGH, MaskType.NAME),
          entry("email", SensitivityLevel.MEDIUM, MaskType.EMAIL),
          entry("phone", SensitivityLevel.MEDIUM, MaskType.PHONE),
          entry("mobile", SensitivityLevel.MEDIUM, MaskType.PHONE),
          entry("celular", SensitivityLevel.MEDIUM, MaskType.PHONE),
          entry("telefono", SensitivityLevel.MEDIUM, MaskType.PHONE));

  /**
   * Palabras que, justo después de un término sensible, describen metadatos sobre él o una forma
   * derivada y protegida en lugar del valor en sí ({@code tokenCount}, {@code passwordPolicy},
   * {@code secretArn}, el índice ciego {@code creditorCciIndex}, {@code panMasked}).
   */
  private static final Set<String> METADATA =
      Set.of(
          "count",
          "counter",
          "attempts",
          "attempt",
          "retries",
          "retry",
          "length",
          "len",
          "size",
          "type",
          "types",
          "kind",
          "format",
          "status",
          "state",
          "enabled",
          "disabled",
          "required",
          "policy",
          "rules",
          "rule",
          "id",
          "ids",
          "name",
          "names",
          "ref",
          "reference",
          "arn",
          "url",
          "uri",
          "endpoint",
          "path",
          "file",
          "location",
          "version",
          "algorithm",
          "alg",
          "ttl",
          "timeout",
          "lifetime",
          "duration",
          "expiry",
          "expires",
          "expiration",
          "expired",
          "at",
          "time",
          "created",
          "updated",
          "issued",
          "changed",
          "rotated",
          "rotation",
          "last",
          "valid",
          "validity",
          "present",
          "provided",
          "used",
          "missing",
          "mode",
          "strategy",
          "provider",
          "source",
          "store",
          "issuer",
          "audience",
          "scope",
          "scopes",
          "enforced",
          "verified",
          "index",
          "masked",
          "mask",
          "truncated");

  /** Término por defecto más largo, en palabras; las ventanas más largas no pueden coincidir. */
  private static final int MAX_WINDOW = 4;

  private static final Pattern SEPARATORS = Pattern.compile("[_\\-.\\s]+");
  private static final Pattern CAMEL =
      Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
  private static final Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");

  private final Map<String, SensitiveField> keys;

  private SensitiveKeys(Map<String, SensitiveField> keys) {
    this.keys = Map.copyOf(keys);
  }

  public static SensitiveKeys defaults() {
    return new SensitiveKeys(DEFAULTS);
  }

  /** Valores por defecto más las claves específicas del servicio (configuración). */
  public static SensitiveKeys withExtra(Map<String, SensitiveField> extra) {
    var merged = new HashMap<>(DEFAULTS);
    extra.forEach((k, v) -> merged.put(normalize(k), v));
    return new SensitiveKeys(merged);
  }

  public Optional<SensitiveField> find(String key) {
    List<String> words = new ArrayList<>();
    for (String part : SEPARATORS.split(key, -1)) {
      for (String word : CAMEL.split(part, -1)) {
        if (!word.isEmpty()) words.add(word.toLowerCase(Locale.ROOT));
      }
    }
    int size = words.size();
    if (size == 0) return Optional.empty();
    SensitiveField whole = lookup(String.join("", words));
    if (whole != null) return Optional.of(whole);
    SensitiveField best = null;
    List<int[]> described = new ArrayList<>(); // términos con palabra de metadatos y sus partes
    for (int length = Math.min(size, MAX_WINDOW); length >= 1; length--) {
      for (int start = 0; start + length <= size; start++) {
        int end = start + length;
        SensitiveField hit = lookup(String.join("", words.subList(start, end)));
        if (hit == null || within(described, start, end)) continue;
        if (end < size && METADATA.contains(stripDigits(words.get(end)))) {
          described.add(new int[] {start, end});
          continue;
        }
        if (best == null || hit.level().compareTo(best.level()) > 0) best = hit;
      }
    }
    return Optional.ofNullable(best);
  }

  private @Nullable SensitiveField lookup(String joined) {
    SensitiveField hit = keys.get(joined);
    return hit != null ? hit : keys.get(stripDigits(joined));
  }

  private static boolean within(List<int[]> spans, int start, int end) {
    for (int[] span : spans) {
      if (start >= span[0] && end <= span[1]) return true;
    }
    return false;
  }

  private static String stripDigits(String word) {
    return TRAILING_DIGITS.matcher(word).replaceFirst("");
  }

  static String normalize(String key) {
    return SEPARATORS.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  private static Map.Entry<String, SensitiveField> entry(String k, SensitivityLevel l, MaskType m) {
    return Map.entry(k, new SensitiveField(k, l, m));
  }
}

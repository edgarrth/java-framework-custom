package pe.axiz.core.sensitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sensitivity by property or JSON key name, for data without annotations (maps, raw HTTP bodies,
 * headers). Matching is case-insensitive and ignores {@code _} and {@code -}.
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
          entry("email", SensitivityLevel.MEDIUM, MaskType.EMAIL),
          entry("phone", SensitivityLevel.MEDIUM, MaskType.PHONE),
          entry("mobile", SensitivityLevel.MEDIUM, MaskType.PHONE),
          entry("celular", SensitivityLevel.MEDIUM, MaskType.PHONE),
          entry("telefono", SensitivityLevel.MEDIUM, MaskType.PHONE));

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

  /** Defaults plus service-specific keys (configuration). */
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
    SensitiveField hit = keys.get(String.join("", words));
    if (hit == null && size > 1) hit = keys.get(words.get(size - 2) + words.get(size - 1));
    if (hit == null) hit = keys.get(words.get(size - 1));
    if (hit == null) hit = keys.get(TRAILING_DIGITS.matcher(words.get(size - 1)).replaceFirst(""));
    return Optional.ofNullable(hit);
  }

  static String normalize(String key) {
    return SEPARATORS.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  private static Map.Entry<String, SensitiveField> entry(String k, SensitivityLevel l, MaskType m) {
    return Map.entry(k, new SensitiveField(k, l, m));
  }
}

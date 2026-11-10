package pe.axiz.core.sensitive;

import java.util.HashMap;
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
          entry("secret", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("clientsecret", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("token", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("accesstoken", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("refreshtoken", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("authorization", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("apikey", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("pin", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cvv", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("cvc", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("otp", SensitivityLevel.CRITICAL, MaskType.FULL),
          entry("pan", SensitivityLevel.HIGH, MaskType.CARD),
          entry("cardnumber", SensitivityLevel.HIGH, MaskType.CARD),
          entry("documentnumber", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("accountnumber", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("cci", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("iban", SensitivityLevel.HIGH, MaskType.DOCUMENT),
          entry("email", SensitivityLevel.MEDIUM, MaskType.EMAIL),
          entry("phone", SensitivityLevel.MEDIUM, MaskType.PHONE),
          entry("mobile", SensitivityLevel.MEDIUM, MaskType.PHONE));

  private static final Pattern SEPARATORS = Pattern.compile("[_\\-.]");

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
    return Optional.ofNullable(keys.get(normalize(key)));
  }

  static String normalize(String key) {
    return SEPARATORS.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  private static Map.Entry<String, SensitiveField> entry(String k, SensitivityLevel l, MaskType m) {
    return Map.entry(k, new SensitiveField(k, l, m));
  }
}

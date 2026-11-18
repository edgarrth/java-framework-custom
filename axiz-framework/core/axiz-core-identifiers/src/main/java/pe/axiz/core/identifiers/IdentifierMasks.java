package pe.axiz.core.identifiers;

import org.jspecify.annotations.Nullable;

/** Canonical display masking shared by identifier value objects and sensitivity policies. */
public final class IdentifierMasks {
  public static final String FULL = "****";

  private IdentifierMasks() {}

  /** At most a quarter of sufficiently long identifiers, capped by the requested suffix. */
  public static String tail(@Nullable String value, int requested) {
    if (value == null || value.length() < 8 || requested < 1) return FULL;
    int visible = Math.min(requested, Math.min(4, value.length() / 4));
    return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
  }

  /** The domain remains visible; a local part shorter than four characters is fully masked. */
  public static String email(@Nullable String value) {
    if (value == null) return FULL;
    int at = value.indexOf('@');
    if (at <= 0 || value.indexOf('@', at + 1) >= 0) return FULL;
    return (at < 4 ? FULL : value.charAt(0) + "*".repeat(at - 1)) + value.substring(at);
  }

  /** Conservative display of a complete PAN: first six and last four digits only. */
  public static String card(@Nullable String value) {
    if (value == null) return FULL;
    String digits = value.replaceAll("\\D", "");
    if (digits.length() < 13 || digits.length() > 19) return FULL;
    return digits.substring(0, 6)
        + "*".repeat(digits.length() - 10)
        + digits.substring(digits.length() - 4);
  }
}

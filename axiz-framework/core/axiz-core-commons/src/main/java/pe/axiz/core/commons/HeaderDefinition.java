package pe.axiz.core.commons;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.lang.Checks;

/**
 * Declares a business header in the header catalog.
 *
 * @param name header name as sent on the wire
 * @param required whether inbound requests must carry it
 * @param pattern optional validation pattern
 * @param propagate whether it is forwarded to outbound calls and messages
 * @param sensitive whether its value must be masked in logs and audit
 * @param maxLength maximum accepted length
 */
public record HeaderDefinition(
    String name,
    boolean required,
    @Nullable Pattern pattern,
    boolean propagate,
    boolean sensitive,
    int maxLength) {

  public HeaderDefinition {
    Checks.notBlank(name, "name");
    Checks.positive(maxLength, "maxLength");
  }

  public static HeaderDefinition optional(String name) {
    return new HeaderDefinition(name, false, null, true, false, 256);
  }

  public static HeaderDefinition required(String name) {
    return new HeaderDefinition(name, true, null, true, false, 256);
  }

  public HeaderDefinition withPattern(String regex) {
    return new HeaderDefinition(
        name, required, Pattern.compile(regex), propagate, sensitive, maxLength);
  }

  public HeaderDefinition notPropagated() {
    return new HeaderDefinition(name, required, pattern, false, sensitive, maxLength);
  }

  public HeaderDefinition asSensitive() {
    return new HeaderDefinition(name, required, pattern, propagate, true, maxLength);
  }

  /** Lowercase key for case-insensitive lookups. */
  public String key() {
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * Validates a header value against this definition.
   *
   * @param value received value (may be null)
   * @return an error message or null when valid
   */
  public @Nullable String validate(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return required ? "Header " + name + " is required" : null;
    }
    if (value.length() > maxLength) {
      return "Header " + name + " exceeds " + maxLength + " characters";
    }
    if (pattern != null && !pattern.matcher(value).matches()) {
      return "Header " + name + " has an invalid format";
    }
    return null;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof HeaderDefinition other && key().equals(other.key());
  }

  @Override
  public int hashCode() {
    return Objects.hash(key());
  }
}

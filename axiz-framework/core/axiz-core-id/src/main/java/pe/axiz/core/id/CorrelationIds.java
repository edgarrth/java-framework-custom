package pe.axiz.core.id;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Correlation and request ids: generation and validation of inbound values. */
public final class CorrelationIds {

  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

  private CorrelationIds() {}

  public static String newId() {
    return UuidV7.generate().toString();
  }

  /** Whether an inbound value is safe to reuse (no spaces, CR/LF or log-injection characters). */
  public static boolean isValid(@Nullable String value) {
    return value != null && SAFE.matcher(value).matches();
  }

  /** Reuses the inbound id when valid, otherwise generates a new one. */
  public static String reuseOrCreate(@Nullable String inbound) {
    return isValid(inbound) ? inbound : newId();
  }
}

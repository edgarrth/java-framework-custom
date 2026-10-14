package pe.axiz.core.commons;

import java.util.Collection;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Argument preconditions that fail fast with {@link IllegalArgumentException}. */
public final class Checks {

  private Checks() {}

  public static <T> T notNull(@Nullable T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  public static String notBlank(@Nullable String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  public static <C extends Collection<?>> C notEmpty(@Nullable C value, String name) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    return value;
  }

  public static void argument(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  public static int positive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive but was " + value);
    }
    return value;
  }

  public static long positive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive but was " + value);
    }
    return value;
  }

  public static int notNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative but was " + value);
    }
    return value;
  }

  public static String maxLength(String value, int max, String name) {
    if (value.length() > max) {
      throw new IllegalArgumentException(name + " must have at most " + max + " characters");
    }
    return value;
  }

  public static String matches(String value, Pattern pattern, String name) {
    if (!pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " has an invalid format");
    }
    return value;
  }
}

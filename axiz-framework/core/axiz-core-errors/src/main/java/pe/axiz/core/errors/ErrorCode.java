package pe.axiz.core.errors;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * Error code with the form {@code PREFIX-CAPABILITY-NNNN}, for example {@code AXZ-HTTP-0001}. The
 * framework uses the {@code AXZ} prefix; services use their own.
 *
 * @param value code text
 */
public record ErrorCode(String value) implements Serializable {

  private static final Pattern FORMAT =
      Pattern.compile("^[A-Z][A-Z0-9]{1,9}-[A-Z0-9]{2,20}-\\d{4}$");

  public ErrorCode {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Invalid error code '" + value + "', expected PREFIX-CAPABILITY-NNNN");
    }
  }

  public static ErrorCode of(String value) {
    return new ErrorCode(value);
  }

  public String prefix() {
    return value.substring(0, value.indexOf('-'));
  }

  @Override
  public String toString() {
    return value;
  }
}

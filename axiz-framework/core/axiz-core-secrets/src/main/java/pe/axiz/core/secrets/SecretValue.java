package pe.axiz.core.secrets;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A secret value. {@link #toString()} never reveals it, so it cannot leak through logs or exception
 * messages by accident.
 */
public final class SecretValue {

  private final char[] value;

  private SecretValue(char[] value) {
    this.value = value;
  }

  public static SecretValue of(String value) {
    return new SecretValue(Objects.requireNonNull(value).toCharArray());
  }

  /** Reveals the value; call it only where the secret is used. */
  public String reveal() {
    return new String(value);
  }

  public char[] revealChars() {
    return value.clone();
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof SecretValue s
        && java.security.MessageDigest.isEqual(
            new String(value).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            new String(s.value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return "SecretValue[****]";
  }
}

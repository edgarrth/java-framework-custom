package pe.axiz.core.secrets;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import pe.axiz.core.sensitive.SelfMasking;

/** Closeable secret with identity equality; clearing memory is best effort, not a JVM guarantee. */
public final class SecretValue implements SelfMasking, AutoCloseable {
  private final char[] value;
  private final Object lock = new Object();
  private boolean closed;

  private SecretValue(char[] value) {
    this.value = value;
  }

  public static SecretValue of(String value) {
    return new SecretValue(Objects.requireNonNull(value).toCharArray());
  }

  public static SecretValue of(char[] value) {
    return new SecretValue(Objects.requireNonNull(value).clone());
  }

  /** Returns an immutable copy which the JVM cannot erase. Prefer use for bounded operations. */
  public String reveal() {
    synchronized (lock) {
      requireOpen();
      return new String(value);
    }
  }

  public char[] revealChars() {
    synchronized (lock) {
      requireOpen();
      return value.clone();
    }
  }

  /**
   * Decodes bounded Base64 without an immutable String; the caller owns and must clear the result.
   */
  public byte[] base64Bytes(int minimum, int maximum) {
    synchronized (lock) {
      requireOpen();
      if (minimum < 1 || maximum < minimum || maximum > 1_048_576)
        throw new IllegalArgumentException("Invalid decoded secret bounds");
      if (value.length < (minimum * 8 + 5) / 6 || value.length > ((maximum + 2) / 3) * 4)
        throw new IllegalArgumentException("Invalid Base64 secret length");
      byte[] ascii = new byte[value.length];
      try {
        for (int i = 0; i < value.length; i++) {
          if (value[i] > 127) throw new IllegalArgumentException("Invalid Base64 secret encoding");
          ascii[i] = (byte) value[i];
        }
        byte[] decoded;
        try {
          decoded = Base64.getDecoder().decode(ascii);
        } catch (IllegalArgumentException malformed) {
          throw new IllegalArgumentException("Invalid Base64 secret encoding");
        }
        if (decoded.length < minimum || decoded.length > maximum) {
          Arrays.fill(decoded, (byte) 0);
          throw new IllegalArgumentException("Invalid decoded secret length");
        }
        return decoded;
      } finally {
        Arrays.fill(ascii, (byte) 0);
      }
    }
  }

  /**
   * Supplies a private copy and erases it on success or failure; the callback must not retain it.
   */
  public <T> T use(Function<char[], T> action) {
    Objects.requireNonNull(action);
    char[] copy = revealChars();
    try {
      return action.apply(copy);
    } finally {
      Arrays.fill(copy, '\0');
    }
  }

  /** Compares snapshots without String conversion or locks held while acquiring another lock. */
  public boolean matches(SecretValue other) {
    Objects.requireNonNull(other);
    char[] left = revealChars();
    char[] right = null;
    try {
      right = other.revealChars();
      int difference = left.length ^ right.length;
      int limit = Math.max(left.length, right.length);
      for (int i = 0; i < limit; i++) {
        difference |= (i < left.length ? left[i] : 0) ^ (i < right.length ? right[i] : 0);
      }
      return difference == 0;
    } finally {
      Arrays.fill(left, '\0');
      if (right != null) Arrays.fill(right, '\0');
    }
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Secret has been closed");
  }

  @Override
  public void close() {
    synchronized (lock) {
      Arrays.fill(value, '\0');
      closed = true;
    }
  }

  @Override
  public String masked() {
    return "****";
  }

  @Override
  public String toString() {
    return "SecretValue[****]";
  }
}

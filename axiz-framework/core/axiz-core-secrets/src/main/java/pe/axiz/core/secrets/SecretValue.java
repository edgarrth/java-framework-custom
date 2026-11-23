package pe.axiz.core.secrets;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import pe.axiz.core.sensitive.SelfMasking;

/**
 * Secreto cerrable con igualdad por identidad; limpiar la memoria es de mejor esfuerzo, no una
 * garantía de la JVM.
 */
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

  /**
   * Devuelve una copia inmutable que la JVM no puede borrar. Es preferible use para operaciones
   * acotadas.
   */
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
   * Decodifica Base64 acotado sin un String inmutable; quien llama es dueño del resultado y debe
   * limpiarlo.
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
   * Suministra una copia privada y la borra en caso de éxito o de fallo; el callback no debe
   * retenerla.
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

  /**
   * Compara snapshots sin conversión a String ni locks retenidos mientras se adquiere otro lock.
   */
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

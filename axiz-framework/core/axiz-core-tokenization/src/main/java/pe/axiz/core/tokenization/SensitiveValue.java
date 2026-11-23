package pe.axiz.core.tokenization;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.sensitive.SelfMasking;

/**
 * Valor textual acotado con propiedad defensiva y borrado de mejor esfuerzo, nunca serialización
 * Java.
 */
public final class SensitiveValue implements SelfMasking, AutoCloseable {
  public static final int MAX_UTF8_BYTES = 8192;
  private final SecretValue secret;

  private SensitiveValue(char[] value) {
    validate(value);
    secret = SecretValue.of(value);
  }

  public static SensitiveValue of(String value) {
    if (value == null || value.length() > MAX_UTF8_BYTES)
      throw TokenErrors.failure(TokenErrors.INVALID);
    char[] copy = value.toCharArray();
    try {
      return of(copy);
    } finally {
      Arrays.fill(copy, '\0');
    }
  }

  public static SensitiveValue of(char[] value) {
    Objects.requireNonNull(value);
    return new SensitiveValue(value);
  }

  private static void validate(char[] value) {
    if (value.length == 0 || value.length > MAX_UTF8_BYTES)
      throw TokenErrors.failure(TokenErrors.INVALID);
    int bytes = 0;
    for (int i = 0; i < value.length; i++) {
      char c = value[i];
      if (Character.isHighSurrogate(c)) {
        if (++i == value.length || !Character.isLowSurrogate(value[i]))
          throw TokenErrors.failure(TokenErrors.INVALID);
        bytes += 4;
      } else if (Character.isLowSurrogate(c)) throw TokenErrors.failure(TokenErrors.INVALID);
      else bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
    }
    if (bytes > MAX_UTF8_BYTES) throw TokenErrors.failure(TokenErrors.INVALID);
  }

  /** El callback recibe una copia privada temporal; no debe retener ese array. */
  public <T> T use(Function<char[], T> action) {
    return secret.use(action);
  }

  public SensitiveValue copy() {
    return use(SensitiveValue::of);
  }

  /**
   * El acceso explícito en la frontera crea un String inmutable que este objeto no puede borrar.
   */
  public String reveal() {
    return secret.reveal();
  }

  @Override
  public void close() {
    secret.close();
  }

  @Override
  public String masked() {
    return "****";
  }

  @Override
  public String toString() {
    return "SensitiveValue[****]";
  }
}

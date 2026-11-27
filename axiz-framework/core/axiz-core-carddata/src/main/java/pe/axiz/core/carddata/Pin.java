package pe.axiz.core.carddata;

import java.util.function.Function;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.CheckDigits;
import pe.axiz.core.secrets.SecretValue;

/** Dato de autenticación de vida corta; no serializable y nunca almacenado. */
public final class Pin implements SensitiveAuthenticationData, AutoCloseable {
  private final SecretValue value;

  private Pin(String value) {
    this.value = SecretValue.of(value);
  }

  public static Pin parse(String value) {
    if (value == null || value.length() < 4 || value.length() > 12 || !CheckDigits.allDigits(value))
      throw new BusinessException(CardDataErrors.INVALID_CODE, "Invalid authentication data");
    return new Pin(value);
  }

  public <T> T use(Function<char[], T> action) {
    return value.use(action);
  }

  @Override
  public void close() {
    value.close();
  }

  @Override
  public String masked() {
    return "***";
  }

  @Override
  public String toString() {
    return masked();
  }
}

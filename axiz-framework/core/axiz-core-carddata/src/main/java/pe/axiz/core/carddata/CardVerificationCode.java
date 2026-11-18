package pe.axiz.core.carddata;

import java.util.function.Function;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.CheckDigits;
import pe.axiz.core.secrets.SecretValue;

/** Short-lived authentication data; not serializable and never stored. */
public final class CardVerificationCode implements SensitiveAuthenticationData, AutoCloseable {
  private final SecretValue value;

  private CardVerificationCode(String value) {
    this.value = SecretValue.of(value);
  }

  public static CardVerificationCode parse(String value) {
    if (value == null || value.length() < 3 || value.length() > 4 || !CheckDigits.allDigits(value))
      throw new BusinessException(CardDataErrors.INVALID_CODE, "Invalid authentication data");
    return new CardVerificationCode(value);
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

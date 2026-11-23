package pe.axiz.core.domain;

import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.contact.ContactIdentifiers;

/**
 * Número de teléfono en formato E.164.
 *
 * @param value número como {@code +51987654321}
 */
public record PhoneNumber(String value) implements ValueObject {

  public PhoneNumber {
    String normalized = value == null ? "" : ContactIdentifiers.PHONE.normalize(value);
    if (!ContactIdentifiers.PHONE.isValid(normalized)) {
      throw new BusinessException(DomainErrors.INVALID_VALUE, "invalid phone");
    }
    value = normalized;
  }

  @Override
  public String toString() {
    return ContactIdentifiers.PHONE.mask(value);
  }
}

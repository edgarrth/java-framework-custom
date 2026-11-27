package pe.axiz.core.domain;

import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.contact.ContactIdentifiers;

/**
 * Dirección de correo validada y normalizada (en minúsculas).
 *
 * @param value dirección
 */
public record EmailAddress(String value) implements ValueObject {

  public EmailAddress {
    String normalized = value == null ? "" : ContactIdentifiers.EMAIL.normalize(value);
    if (!ContactIdentifiers.EMAIL.isValid(normalized)) {
      throw new BusinessException(DomainErrors.INVALID_VALUE, "invalid email");
    }
    value = normalized;
  }

  public String masked() {
    return ContactIdentifiers.EMAIL.mask(value);
  }

  @Override
  public String toString() {
    return masked();
  }
}

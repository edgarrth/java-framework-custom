package pe.axiz.core.domain;

import java.util.Locale;
import java.util.Map;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.identifiers.peru.PeruIdentifiers;

/**
 * Identity document of a person or company (DNI, RUC or foreigner card).
 *
 * @param type document type
 * @param number normalized number
 */
public record PersonalDocument(Type type, String number) implements ValueObject {

  /** Supported document types. */
  public enum Type {
    DNI,
    RUC,
    CE;

    /** Validation and masking rules of the document type. */
    IdentifierType identifier() {
      return switch (this) {
        case DNI -> PeruIdentifiers.DNI;
        case RUC -> PeruIdentifiers.RUC;
        case CE -> PeruIdentifiers.CE;
      };
    }

    public static Type parse(String value) {
      return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
  }

  public PersonalDocument {
    if (type == null || number == null) {
      throw new BusinessException(DomainErrors.INVALID_VALUE, "document type and number required");
    }
    String normalized = type.identifier().normalize(number);
    if (!type.identifier().isValid(normalized)) {
      throw new BusinessException(
          DomainErrors.INVALID_VALUE, "invalid document", Map.of("type", type.name()));
    }
    number = normalized;
  }

  public String masked() {
    return type.identifier().mask(number);
  }

  @Override
  public String toString() {
    return type + ":" + masked();
  }
}

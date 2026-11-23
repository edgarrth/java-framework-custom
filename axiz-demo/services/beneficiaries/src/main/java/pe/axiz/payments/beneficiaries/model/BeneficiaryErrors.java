package pe.axiz.payments.beneficiaries.model;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Catálogo de errores de beneficiaries (códigos {@code PAYMENTS-BENEFICIARIES-NNNN}); los detalles
 * son las claves {@code error.<code>} de los paquetes de mensajes.
 */
public final class BeneficiaryErrors {

  /** La cuenta no es un CCI válido (dígitos de control). */
  public static final ErrorDefinition INVALID_ACCOUNT =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIES-0001", ErrorCategory.UNPROCESSABLE, "Invalid account");

  /** El cliente ya tiene un beneficiario activo con esa cuenta. */
  public static final ErrorDefinition DUPLICATE_ACCOUNT =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIES-0002", ErrorCategory.CONFLICT, "Beneficiary already exists");

  /** El cliente alcanzó el número máximo de beneficiarios. */
  public static final ErrorDefinition LIMIT_REACHED =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIES-0003", ErrorCategory.UNPROCESSABLE, "Beneficiary limit reached");

  /** El cursor de paginación está mal formado o pertenece a otro cliente. */
  public static final ErrorDefinition INVALID_CURSOR =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIES-0004", ErrorCategory.BAD_REQUEST, "Invalid cursor");

  private BeneficiaryErrors() {}
}

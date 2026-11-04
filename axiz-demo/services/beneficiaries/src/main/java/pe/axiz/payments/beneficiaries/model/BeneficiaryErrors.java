package pe.axiz.payments.beneficiaries.model;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Error catalog of beneficiaries-crud (codes {@code PAYMENTS-BENEFICIARIESCRUD-NNNN}); details are
 * the {@code error.<code>} keys of the message bundles.
 */
public final class BeneficiaryErrors {

  /** The account is not a valid CCI (check digits). */
  public static final ErrorDefinition INVALID_ACCOUNT =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIESCRUD-0001", ErrorCategory.UNPROCESSABLE, "Invalid account");

  /** The customer already has an active beneficiary with that account. */
  public static final ErrorDefinition DUPLICATE_ACCOUNT =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIESCRUD-0002", ErrorCategory.CONFLICT, "Beneficiary already exists");

  /** The customer reached the maximum number of beneficiaries. */
  public static final ErrorDefinition LIMIT_REACHED =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIESCRUD-0003",
          ErrorCategory.UNPROCESSABLE,
          "Beneficiary limit reached");

  /** The pagination cursor is malformed or belongs to another customer. */
  public static final ErrorDefinition INVALID_CURSOR =
      ErrorDefinition.of(
          "PAYMENTS-BENEFICIARIESCRUD-0004", ErrorCategory.BAD_REQUEST, "Invalid cursor");

  private BeneficiaryErrors() {}
}

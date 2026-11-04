package pe.axiz.payments.beneficiaries.model;

/**
 * Per-customer rules of beneficiaries.
 *
 * @param maxPerCustomer active beneficiaries a customer may have
 */
public record BeneficiaryPolicy(int maxPerCustomer) {

  public BeneficiaryPolicy {
    if (maxPerCustomer < 1) {
      throw new IllegalArgumentException("maxPerCustomer must be positive");
    }
  }
}

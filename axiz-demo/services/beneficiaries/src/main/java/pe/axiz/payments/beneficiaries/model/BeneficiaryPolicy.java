package pe.axiz.payments.beneficiaries.model;

import java.util.List;
import java.util.Map;
import pe.axiz.core.errors.BusinessException;

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

  /** Apply inside the repository's customer snapshot/transaction boundary. */
  public void checkRegistration(List<Beneficiary> active, String cci) {
    if (active.stream().anyMatch(b -> b.sameAccount(cci)))
      throw new BusinessException(BeneficiaryErrors.DUPLICATE_ACCOUNT, "duplicate account");
    if (active.size() >= maxPerCustomer)
      throw new BusinessException(
          BeneficiaryErrors.LIMIT_REACHED,
          "limit reached",
          Map.of("max", Integer.toString(maxPerCustomer)));
  }
}

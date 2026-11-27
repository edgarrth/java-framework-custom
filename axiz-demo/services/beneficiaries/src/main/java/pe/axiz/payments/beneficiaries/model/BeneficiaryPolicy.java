package pe.axiz.payments.beneficiaries.model;

import java.util.List;
import java.util.Map;
import pe.axiz.core.errors.BusinessException;

/**
 * Reglas de beneficiarios por cliente.
 *
 * @param maxPerCustomer beneficiarios activos que puede tener un cliente
 */
public record BeneficiaryPolicy(int maxPerCustomer) {

  public BeneficiaryPolicy {
    if (maxPerCustomer < 1) {
      throw new IllegalArgumentException("maxPerCustomer must be positive");
    }
  }

  /**
   * Se aplica dentro del límite de snapshot y transacción del cliente que define el repositorio.
   */
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

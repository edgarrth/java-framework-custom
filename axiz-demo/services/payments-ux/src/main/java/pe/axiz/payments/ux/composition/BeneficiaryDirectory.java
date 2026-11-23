package pe.axiz.payments.ux.composition;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.cache.AxizCacheable;
import pe.axiz.payments.ux.client.beneficiaries.BeneficiariesClient;
import pe.axiz.payments.ux.client.beneficiaries.model.Beneficiary;
import pe.axiz.payments.ux.client.beneficiaries.model.BeneficiaryPage;
import reactor.core.publisher.Mono;

/**
 * Lee beneficiarios desde beneficiaries. Las páginas se guardan en caché por poco tiempo (dos
 * niveles: memoria y Redis, TTL en {@code axiz.cache.caches.customer-beneficiaries}) porque los
 * canales las listan en cada pantalla de pago; los beneficiarios individuales siempre se leen
 * frescos previo al pago.
 */
@Component
public class BeneficiaryDirectory {

  private final BeneficiariesClient beneficiaries;

  public BeneficiaryDirectory(BeneficiariesClient beneficiaries) {
    this.beneficiaries = beneficiaries;
  }

  /** Una página de los beneficiarios del cliente (en caché). */
  @AxizCacheable("customer-beneficiaries")
  public Mono<BeneficiaryPage> page(String customerId, @Nullable String cursor, int size) {
    return beneficiaries.listBeneficiaries(customerId, cursor, size);
  }

  /** Un beneficiario, nunca desde la caché: los pagos deben ver las eliminaciones de inmediato. */
  public Mono<Beneficiary> get(String customerId, String beneficiaryId) {
    return beneficiaries
        .getBeneficiary(customerId, beneficiaryId)
        .onErrorMap(e -> DownstreamErrors.translate(e, "beneficiary", beneficiaryId));
  }
}

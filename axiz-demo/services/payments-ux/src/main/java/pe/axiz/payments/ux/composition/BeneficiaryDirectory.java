package pe.axiz.payments.ux.composition;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.cache.AxizCacheable;
import pe.axiz.payments.ux.client.beneficiaries.BeneficiariesClient;
import pe.axiz.payments.ux.client.beneficiaries.model.Beneficiary;
import pe.axiz.payments.ux.client.beneficiaries.model.BeneficiaryPage;
import reactor.core.publisher.Mono;

/**
 * Reads beneficiaries from beneficiaries. Pages are cached briefly (two levels: memory and Redis,
 * TTL in {@code axiz.cache.caches.customer-beneficiaries}) because channels list them on every
 * payment screen; single beneficiaries are always read fresh before paying.
 */
@Component
public class BeneficiaryDirectory {

  private final BeneficiariesClient beneficiaries;

  public BeneficiaryDirectory(BeneficiariesClient beneficiaries) {
    this.beneficiaries = beneficiaries;
  }

  /** One page of the customer's beneficiaries (cached). */
  @AxizCacheable("customer-beneficiaries")
  public Mono<BeneficiaryPage> page(String customerId, @Nullable String cursor, int size) {
    return beneficiaries.listBeneficiaries(customerId, cursor, size);
  }

  /** One beneficiary, never from cache: payments must see removals immediately. */
  public Mono<Beneficiary> get(String customerId, String beneficiaryId) {
    return beneficiaries
        .getBeneficiary(customerId, beneficiaryId)
        .onErrorMap(e -> DownstreamErrors.translate(e, "beneficiary", beneficiaryId));
  }
}

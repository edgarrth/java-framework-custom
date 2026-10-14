package pe.axiz.payments.experience.composition;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.payments.experience.api.model.BeneficiaryList;
import reactor.core.publisher.Mono;

/** Beneficiary screens of the channels: the signed-in customer's list, masked. */
@Component
public class BeneficiariesComposition {

  private final BeneficiaryDirectory directory;

  public BeneficiariesComposition(BeneficiaryDirectory directory) {
    this.directory = directory;
  }

  /** Frequent beneficiaries of the signed-in customer. */
  public Mono<BeneficiaryList> listMyBeneficiaries(@Nullable String cursor, PageRequest page) {
    return Customer.current()
        .flatMap(customer -> directory.page(customer.id(), cursor, page.size()))
        .map(ChannelViews::beneficiaries);
  }
}

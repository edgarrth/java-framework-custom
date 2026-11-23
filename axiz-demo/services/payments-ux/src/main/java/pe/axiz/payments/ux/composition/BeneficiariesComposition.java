package pe.axiz.payments.ux.composition;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.api.PageRequest;
import pe.axiz.payments.ux.composition.model.BeneficiaryList;
import reactor.core.publisher.Mono;

/** Pantallas de beneficiarios de los canales: la lista del cliente autenticado, enmascarada. */
@Component
public class BeneficiariesComposition {

  private final BeneficiaryDirectory directory;

  public BeneficiariesComposition(BeneficiaryDirectory directory) {
    this.directory = directory;
  }

  /** Beneficiarios frecuentes del cliente autenticado. */
  public Mono<BeneficiaryList> listMyBeneficiaries(@Nullable String cursor, PageRequest page) {
    return Customer.current()
        .flatMap(customer -> directory.page(customer.id(), cursor, page.size()))
        .map(ChannelViews::beneficiaries);
  }
}

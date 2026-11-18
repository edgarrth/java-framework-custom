package pe.axiz.payments.beneficiaries.controller;

import pe.axiz.core.api.Page;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.NewBeneficiary;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryPage;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryRequest;

/** Maps between the contract models and the service model. */
final class BeneficiaryApiMapper {

  private BeneficiaryApiMapper() {}

  static NewBeneficiary toNew(BeneficiaryRequest body) {
    return new NewBeneficiary(
        body.getAlias(), body.getHolderName(), body.getCci(), body.getCurrency());
  }

  static pe.axiz.payments.beneficiaries.model.api.Beneficiary toApi(Beneficiary b) {
    return new pe.axiz.payments.beneficiaries.model.api.Beneficiary()
        .id(b.getBeneficiaryId())
        .customerId(b.getCustomerId())
        .alias(b.getAlias())
        .holderName(b.getHolderName())
        .cci(b.getCci())
        .currency(b.getCurrency());
  }

  static BeneficiaryPage toApi(Page<Beneficiary> page) {
    return new BeneficiaryPage()
        .items(page.content().stream().map(BeneficiaryApiMapper::toApi).toList())
        .nextCursor(page.nextCursor());
  }
}

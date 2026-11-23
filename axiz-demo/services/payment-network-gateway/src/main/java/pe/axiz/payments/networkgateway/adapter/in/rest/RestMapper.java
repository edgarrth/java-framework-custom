package pe.axiz.payments.networkgateway.adapter.in.rest;

import pe.axiz.core.money.Money;
import pe.axiz.payments.networkgateway.adapter.in.rest.model.Transfer;
import pe.axiz.payments.networkgateway.adapter.in.rest.model.TransferRequest;
import pe.axiz.payments.networkgateway.domain.TransferOrder;

/** Convierte la frontera HTTP en valores de dominio validados. */
final class RestMapper {
  private RestMapper() {}

  static TransferOrder order(TransferRequest request) {
    return new TransferOrder(
        request.getCreditorCci(),
        Money.of(request.getAmount(), request.getCurrency()),
        request.getReference());
  }

  static Transfer toApi(pe.axiz.payments.networkgateway.domain.Transfer transfer) {
    return new Transfer()
        .transferId(transfer.id())
        .status(transfer.status().name())
        .reference(transfer.reference());
  }
}

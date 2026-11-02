package pe.axiz.payments.coreintegration.adapter.in.rest;

import pe.axiz.core.money.Money;
import pe.axiz.payments.coreintegration.api.model.Movement;
import pe.axiz.payments.coreintegration.api.model.MovementRequest;
import pe.axiz.payments.coreintegration.api.model.Transfer;
import pe.axiz.payments.coreintegration.api.model.TransferRequest;
import pe.axiz.payments.coreintegration.domain.MovementOrder;
import pe.axiz.payments.coreintegration.domain.TransferOrder;

/** Maps contract models to domain values and back. */
final class RestMapper {

  private RestMapper() {}

  static MovementOrder order(MovementRequest r) {
    return new MovementOrder(
        r.getAccountId(),
        Money.ofExact(r.getAmount(), r.getCurrency()),
        r.getReference(),
        r.getHolderId());
  }

  static TransferOrder order(TransferRequest r) {
    return new TransferOrder(
        r.getCreditorCci(), Money.ofExact(r.getAmount(), r.getCurrency()), r.getReference());
  }

  static Movement toApi(pe.axiz.payments.coreintegration.domain.Movement m) {
    return new Movement().movementId(m.id()).status(m.status().name()).reference(m.reference());
  }

  static Transfer toApi(pe.axiz.payments.coreintegration.domain.Transfer t) {
    return new Transfer().transferId(t.id()).status(t.status().name()).reference(t.reference());
  }
}

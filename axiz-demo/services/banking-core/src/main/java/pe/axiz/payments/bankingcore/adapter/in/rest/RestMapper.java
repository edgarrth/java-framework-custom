package pe.axiz.payments.bankingcore.adapter.in.rest;

import pe.axiz.core.money.Money;
import pe.axiz.payments.bankingcore.api.model.Movement;
import pe.axiz.payments.bankingcore.api.model.MovementRequest;
import pe.axiz.payments.bankingcore.domain.MovementOrder;

/** Maps contract models to domain values and back. */
final class RestMapper {

  private RestMapper() {}

  static MovementOrder order(MovementRequest r) {
    return new MovementOrder(
        r.getAccountId(),
        Money.of(r.getAmount(), r.getCurrency()),
        r.getReference(),
        r.getHolderId());
  }

  static Movement toApi(pe.axiz.payments.bankingcore.domain.Movement m) {
    return new Movement().movementId(m.id()).status(m.status().name()).reference(m.reference());
  }
}

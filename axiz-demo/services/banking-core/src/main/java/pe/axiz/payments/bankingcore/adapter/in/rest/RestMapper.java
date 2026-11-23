package pe.axiz.payments.bankingcore.adapter.in.rest;

import pe.axiz.core.money.Money;
import pe.axiz.payments.bankingcore.adapter.in.rest.model.Movement;
import pe.axiz.payments.bankingcore.adapter.in.rest.model.MovementRequest;
import pe.axiz.payments.bankingcore.domain.MovementOrder;

/** Convierte los modelos del contrato en valores de dominio y viceversa. */
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

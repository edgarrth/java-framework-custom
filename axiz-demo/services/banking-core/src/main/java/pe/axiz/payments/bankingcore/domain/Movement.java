package pe.axiz.payments.bankingcore.domain;

import java.util.Objects;

/**
 * Movimiento contabilizado por el sistema core bancario.
 *
 * @param id id del movimiento en el core
 * @param status contabilizado o revertido
 * @param reference referencia de negocio (número de operación o id del movimiento revertido)
 */
public record Movement(String id, MovementStatus status, String reference) {

  public Movement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(reference, "reference");
  }
}

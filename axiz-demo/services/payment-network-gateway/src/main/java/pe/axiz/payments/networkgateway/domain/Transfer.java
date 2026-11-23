package pe.axiz.payments.networkgateway.domain;

import java.util.Objects;

/**
 * Transferencia aceptada por la red de pagos.
 *
 * @param id id en la red
 * @param status liquidada o pendiente
 * @param reference referencia de extremo a extremo
 */
public record Transfer(String id, TransferStatus status, String reference) {

  public Transfer {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(reference, "reference");
  }
}

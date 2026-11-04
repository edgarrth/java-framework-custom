package pe.axiz.payments.networkgateway.domain;

import java.util.Objects;

/**
 * A transfer accepted by the payment network.
 *
 * @param id network id
 * @param status settled or pending
 * @param reference end-to-end reference
 */
public record Transfer(String id, TransferStatus status, String reference) {

  public Transfer {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(reference, "reference");
  }
}

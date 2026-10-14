package pe.axiz.payments.coreintegration.domain;

import java.util.Objects;

/**
 * A movement posted by the core banking system.
 *
 * @param id core movement id
 * @param status posted or reversed
 * @param reference business reference (operation number or the reversed movement id)
 */
public record Movement(String id, MovementStatus status, String reference) {

  public Movement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(reference, "reference");
  }
}

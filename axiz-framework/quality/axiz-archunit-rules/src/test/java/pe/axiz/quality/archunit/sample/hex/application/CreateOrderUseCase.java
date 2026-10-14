package pe.axiz.quality.archunit.sample.hex.application;

import pe.axiz.quality.archunit.sample.hex.domain.Order;

public final class CreateOrderUseCase {
  public Order execute(String id) {
    return new Order(id);
  }
}

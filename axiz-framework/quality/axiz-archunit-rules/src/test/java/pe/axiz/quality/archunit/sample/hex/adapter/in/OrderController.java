package pe.axiz.quality.archunit.sample.hex.adapter.in;

import pe.axiz.quality.archunit.sample.hex.application.CreateOrderUseCase;

public final class OrderController {
  private final CreateOrderUseCase useCase;

  public OrderController(CreateOrderUseCase useCase) {
    this.useCase = useCase;
  }

  public String create(String id) {
    return useCase.execute(id).id();
  }
}

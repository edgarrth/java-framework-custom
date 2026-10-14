package pe.axiz.quality.archunit.sample.hex.domain;

import pe.axiz.quality.archunit.sample.hex.adapter.in.OrderController;

/** Violates the inward dependency rule on purpose. */
public final class BadDomain {
  public String name() {
    return OrderController.class.getSimpleName();
  }
}

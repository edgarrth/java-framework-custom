package pe.axiz.quality.archunit.sample.hex.domain;

import pe.axiz.quality.archunit.sample.hex.adapter.in.OrderController;

/** Incumple a propósito la regla de dependencias hacia dentro. */
public final class BadDomain {
  public String name() {
    return OrderController.class.getSimpleName();
  }
}

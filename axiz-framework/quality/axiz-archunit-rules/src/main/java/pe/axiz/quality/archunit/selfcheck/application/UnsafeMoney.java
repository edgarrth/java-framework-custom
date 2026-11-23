package pe.axiz.quality.archunit.selfcheck.application;

import java.math.BigDecimal;

/**
 * Fixture prohibido de forma deliberada, que solo importa como bytecode la autoverificación de
 * arquitectura.
 */
public final class UnsafeMoney {
  private final double amount;

  public UnsafeMoney(double amount) {
    this.amount = amount;
  }

  public BigDecimal decimal() {
    return BigDecimal.valueOf(amount);
  }
}

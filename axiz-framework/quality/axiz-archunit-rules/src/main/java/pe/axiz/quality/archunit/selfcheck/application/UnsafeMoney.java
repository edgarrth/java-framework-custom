package pe.axiz.quality.archunit.selfcheck.application;

import java.math.BigDecimal;

/** Deliberately prohibited fixture, imported as bytecode by the architecture self-check only. */
public final class UnsafeMoney {
  private final double amount;

  public UnsafeMoney(double amount) {
    this.amount = amount;
  }

  public BigDecimal decimal() {
    return BigDecimal.valueOf(amount);
  }
}

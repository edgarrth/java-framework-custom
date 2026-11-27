package pe.axiz.quality.archunit.sample.guarded.model;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

public record Amount(BigDecimal amount) {
  public Instant time(Clock clock) {
    return Instant.now(clock);
  }
}

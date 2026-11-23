package pe.axiz.quality.archunit.sample.guarded.domain;

import java.time.Clock;
import java.time.Instant;

/** La forma prevista: el tiempo proviene de un reloj inyectado. */
public class InjectedTime {
  private final Clock clock;

  public InjectedTime(Clock clock) {
    this.clock = clock;
  }

  public Instant now() {
    return Instant.now(clock);
  }
}

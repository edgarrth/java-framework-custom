package pe.axiz.quality.archunit.sample.guarded.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class Ambient {
  double amount;

  public String configuration() {
    return System.getenv("REGION");
  }

  public Instant time() {
    return Instant.now();
  }

  public BigDecimal rounded() {
    return BigDecimal.valueOf(0.1d);
  }
}

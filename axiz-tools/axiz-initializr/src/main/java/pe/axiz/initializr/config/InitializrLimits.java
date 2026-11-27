package pe.axiz.initializr.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Presupuestos por sujeto autenticado, compartidos entre réplicas mediante Redis. */
@ConfigurationProperties("axiz.initializr.limits")
public record InitializrLimits(
    @DefaultValue("1m") Duration window,
    @DefaultValue("10") int generation,
    @DefaultValue("2") int publication,
    @DefaultValue("20") int validation,
    @DefaultValue("4") int concurrency) {
  public InitializrLimits {
    if (window == null
        || window.compareTo(Duration.ofSeconds(1)) < 0
        || window.compareTo(Duration.ofHours(1)) > 0
        || generation < 1
        || publication < 1
        || validation < 1
        || concurrency < 1
        || concurrency > 64)
      throw new IllegalArgumentException("Invalid Initializr operation budgets");
  }
}

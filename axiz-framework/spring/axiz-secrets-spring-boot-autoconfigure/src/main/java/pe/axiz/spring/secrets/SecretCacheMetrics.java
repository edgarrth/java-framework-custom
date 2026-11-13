package pe.axiz.spring.secrets;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import pe.axiz.core.secrets.SecretCacheObserver;

/** Only bounded outcome and resolver labels; measurements never receive a secret identifier. */
final class SecretCacheMetrics implements SecretCacheObserver {
  private final MeterRegistry registry;
  private final String resolver;

  SecretCacheMetrics(MeterRegistry registry, String resolver) {
    this.registry = registry;
    this.resolver = resolver;
  }

  @Override
  public void hit() {
    request("hit");
  }

  @Override
  public void miss() {
    request("miss");
  }

  private void request(String result) {
    registry
        .counter("axiz.secrets.cache.requests", "result", result, "resolver", resolver)
        .increment();
  }

  @Override
  public void loaded(LoadOutcome outcome, Duration elapsed) {
    registry
        .timer(
            "axiz.secrets.cache.loads",
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT),
            "resolver",
            resolver)
        .record(elapsed);
  }
}

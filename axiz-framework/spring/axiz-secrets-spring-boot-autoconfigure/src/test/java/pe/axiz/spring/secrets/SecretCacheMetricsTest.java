package pe.axiz.spring.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.secrets.CachingSecretResolver;
import pe.axiz.core.secrets.SecretValue;

class SecretCacheMetricsTest {
  @Test
  void metricsHaveBoundedLabelsAndCountOneLoadForRepeatedHits() {
    var registry = new SimpleMeterRegistry();
    try {
      var cache =
          new CachingSecretResolver(
              name -> Optional.of(SecretValue.of("fixture-value")),
              Duration.ofMinutes(1),
              Clock.systemUTC(),
              new SecretCacheMetrics(registry, "sync"));
      cache.resolve("identifier-that-must-never-be-a-tag");
      cache.resolve("identifier-that-must-never-be-a-tag");
      assertThat(
              registry.get("axiz.secrets.cache.requests").tag("result", "miss").counter().count())
          .isEqualTo(1);
      assertThat(registry.get("axiz.secrets.cache.requests").tag("result", "hit").counter().count())
          .isEqualTo(1);
      assertThat(registry.get("axiz.secrets.cache.loads").tag("outcome", "found").timer().count())
          .isEqualTo(1);
      for (var meter : registry.getMeters()) {
        assertThat(meter.getId().getTags())
            .allMatch(
                tag -> java.util.Set.of("result", "outcome", "resolver").contains(tag.getKey()));
        assertThat(meter.getId().toString())
            .doesNotContain("identifier-that-must-never-be-a-tag", "fixture-value");
      }
    } finally {
      registry.close();
    }
  }
}

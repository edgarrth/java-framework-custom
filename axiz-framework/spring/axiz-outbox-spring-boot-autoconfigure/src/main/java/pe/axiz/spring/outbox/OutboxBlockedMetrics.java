package pe.axiz.spring.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.outbox.OutboxStore;

/** Blocked aggregate visibility; scrapes never perform database I/O. */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.outbox.AxizOutboxAutoConfiguration",
      "pe.axiz.spring.outbox.AxizOutboxR2dbcAutoConfiguration"
    })
@ConditionalOnBean(OutboxStore.class)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@EnableConfigurationProperties(AxizOutboxProperties.class)
@ConditionalOnBooleanProperty(name = "axiz.outbox.enabled", matchIfMissing = true)
public class OutboxBlockedMetrics {
  @Bean
  MeterBinder axizOutboxBlockedMetrics(OutboxStore store, ObjectProvider<Clock> clock) {
    var count =
        new CachedPendingCount(
            store,
            Duration.ofSeconds(10),
            clock.getIfAvailable(Clock::systemUTC),
            OutboxStore::countBlockedAggregates);
    var skipped =
        new CachedPendingCount(
            store,
            Duration.ofSeconds(10),
            clock.getIfAvailable(Clock::systemUTC),
            OutboxStore::countSkipped);
    return registry -> {
      Gauge.builder("axiz.outbox.skipped", skipped, CachedPendingCount::value)
          .description("Explicitly skipped outbox rows retained as terminal evidence")
          .register(registry);
      Gauge.builder("axiz.outbox.blocked.aggregates", count, CachedPendingCount::value)
          .description("Aggregates blocked by a permanently failed outbox event")
          .register(registry);
    };
  }
}

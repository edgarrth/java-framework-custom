package pe.axiz.spring.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.outbox.OutboxRelayListener;

/** Las métricas son un binding opcional del listener neutral del relay. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
class OutboxRelayMetricsConfiguration {
  @Bean
  @ConditionalOnMissingBean(OutboxRelayListener.class)
  OutboxRelayListener axizOutboxRelayListener(MeterRegistry registry) {
    Timer claims = registry.timer("axiz.outbox.relay.claim.duration");
    Counter exhausted = registry.counter("axiz.outbox.relay.budget.exhausted");
    return new OutboxRelayListener() {
      @Override
      public void onClaim(Duration duration, int rows) {
        claims.record(duration);
      }

      @Override
      public void onBudgetExhausted(int skipped) {
        exhausted.increment();
      }
    };
  }
}

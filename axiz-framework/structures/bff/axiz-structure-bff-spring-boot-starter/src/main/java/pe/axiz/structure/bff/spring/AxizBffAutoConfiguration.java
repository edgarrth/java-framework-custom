package pe.axiz.structure.bff.spring;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * BFF style: context-aware parallel composition. HTTP clients (token relay, resilience), read cache
 * and channel handling come from the composed starters.
 */
@AutoConfiguration
public class AxizBffAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Compositions axizCompositions(Environment env) {
    ContextSnapshotFactory snapshots =
        ContextSnapshotFactory.builder().contextRegistry(ContextRegistry.getInstance()).build();
    // Boot's binder converts "500ms" or "3s" in any context (Environment.getProperty only when the
    // environment carries Boot's conversion service)
    Duration timeout =
        Binder.get(env)
            .bind("axiz.bff.timeout", Duration.class)
            .orElseGet(() -> Duration.ofSeconds(3));
    return new Compositions(snapshots, timeout);
  }
}

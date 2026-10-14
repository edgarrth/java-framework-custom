package pe.axiz.framework.bff.spring;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
    return new Compositions(
        snapshots, env.getProperty("axiz.bff.timeout", Duration.class, Duration.ofSeconds(3)));
  }
}

package pe.axiz.framework.eda.spring;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * EDA style settings ({@code axiz.eda.*}).
 *
 * @param destination default destination of reactions
 * @param routes destination by reaction event type
 * @param transactional whether handlers run in a transaction (with the outbox)
 * @param handlerTimeout bound of one reactive handling, awaited on the consumer thread
 */
@ConfigurationProperties("axiz.eda")
public record AxizEdaProperties(
    @DefaultValue("domain-events") String destination,
    @DefaultValue Map<String, String> routes,
    @DefaultValue("true") boolean transactional,
    @DefaultValue("30s") Duration handlerTimeout) {
  public AxizEdaProperties {
    routes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(routes));
  }
}

package pe.axiz.spring.observability;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Observability conventions ({@code axiz.observability.*}).
 *
 * @param deniedAttributes extra attribute keys never exported (PII protection)
 * @param ignoredPaths inbound paths not observed (probes, metrics scraping)
 * @param contextAttributes whether correlation id, channel and tenant are added to spans
 */
@ConfigurationProperties("axiz.observability")
public record AxizObservabilityProperties(
    @DefaultValue Set<String> deniedAttributes,
    @DefaultValue({"/actuator"}) List<String> ignoredPaths,
    @DefaultValue("true") boolean contextAttributes) {
  public AxizObservabilityProperties {
    deniedAttributes = Collections.unmodifiableSet(new LinkedHashSet<>(deniedAttributes));
    ignoredPaths = List.copyOf(ignoredPaths);
  }
}

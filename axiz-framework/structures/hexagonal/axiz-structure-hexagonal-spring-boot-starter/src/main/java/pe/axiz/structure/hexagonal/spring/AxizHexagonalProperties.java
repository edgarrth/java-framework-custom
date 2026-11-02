package pe.axiz.structure.hexagonal.spring;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Hexagonal style settings ({@code axiz.hexagonal.*}).
 *
 * @param events domain event publication through the outbox
 */
@ConfigurationProperties("axiz.hexagonal")
public record AxizHexagonalProperties(@DefaultValue Events events) {

  /**
   * Domain event routing.
   *
   * @param destination default logical destination
   * @param destinations destination by event type
   * @param typeSuffix version suffix of event types, e.g. {@code .v1}
   */
  public record Events(
      @DefaultValue("domain-events") String destination,
      @DefaultValue Map<String, String> destinations,
      @DefaultValue(".v1") String typeSuffix) {
    public Events {
      destinations =
          java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(destinations));
    }
  }
}

package pe.axiz.spring.logging;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Logging conventions ({@code axiz.logging.*}).
 *
 * @param json whether console output is structured JSON; by default only inside Kubernetes
 * @param http HTTP request/response logging
 */
@ConfigurationProperties("axiz.logging")
public record AxizLoggingProperties(@Nullable Boolean json, @DefaultValue Http http) {

  /**
   * HTTP access log.
   *
   * @param enabled whether one line per request is written (logger {@code axiz.http})
   * @param headers allow-listed headers to include; Authorization and Cookie are never written
   * @param bodies whether JSON bodies are included (masked and truncated; servlet only)
   * @param maxBodyLength maximum logged body length
   * @param excludedPaths path patterns not logged
   */
  public record Http(
      @DefaultValue("true") boolean enabled,
      @DefaultValue({"Content-Type", "Accept", "User-Agent", "X-Correlation-Id", "X-Request-Id"})
          List<String> headers,
      @DefaultValue("false") boolean bodies,
      @DefaultValue("4000") int maxBodyLength,
      @DefaultValue({"/actuator/**"}) List<String> excludedPaths) {}
}

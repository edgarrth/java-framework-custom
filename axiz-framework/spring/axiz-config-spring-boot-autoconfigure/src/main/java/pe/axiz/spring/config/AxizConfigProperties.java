package pe.axiz.spring.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Logical environment and properties required by the application at startup. */
@ConfigurationProperties("axiz.config")
public record AxizConfigProperties(String environment, @DefaultValue List<String> required) {
  public AxizConfigProperties {
    required = List.copyOf(required);
  }
}

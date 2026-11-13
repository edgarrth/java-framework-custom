package pe.axiz.spring.foundation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;

/** Native HTTP transport settings, independent of callback executor configuration. */
@ConfigurationProperties("axiz.aws.async")
public record AxizAwsAsyncTransportProperties(
    @DefaultValue("5m") Duration connectionTtl, @DefaultValue("30s") Duration maxIdleTime) {
  public AxizAwsAsyncTransportProperties {
    new AsyncAwsTransportSettings(connectionTtl, maxIdleTime);
  }

  public AsyncAwsTransportSettings toSettings() {
    return new AsyncAwsTransportSettings(connectionTtl, maxIdleTime);
  }
}

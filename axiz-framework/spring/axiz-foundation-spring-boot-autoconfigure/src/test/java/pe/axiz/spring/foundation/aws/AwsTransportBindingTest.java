package pe.axiz.spring.foundation.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import pe.axiz.spring.foundation.AxizAwsAsyncTransportProperties;

class AwsTransportBindingTest {
  @Test
  void usesSpringDurationBindingIncludingBareMilliseconds() {
    for (var entry :
        Map.of(
                "100",
                Duration.ofMillis(100),
                "100ms",
                Duration.ofMillis(100),
                "PT0.1S",
                Duration.ofMillis(100),
                "2s",
                Duration.ofSeconds(2),
                "3m",
                Duration.ofMinutes(3),
                "1h",
                Duration.ofHours(1))
            .entrySet()) {
      var properties =
          new Binder(
                  new MapConfigurationPropertySource(
                      Map.of("axiz.aws.async.connection-ttl", entry.getKey())))
              .bind("axiz.aws.async", AxizAwsAsyncTransportProperties.class)
              .orElseThrow(IllegalStateException::new);
      assertEquals(entry.getValue(), properties.toSettings().connectionTtl());
      assertEquals(Duration.ofSeconds(30), properties.toSettings().maxIdleTime());
    }
  }

  @Test
  void rejectsNonPositiveAndInvalidValues() {
    for (String value :
        new String[] {"0ms", "PT0S", "-1s", "PT0.000000001S", "invalid", "999999999999999999999h"})
      assertThrows(
          RuntimeException.class,
          () ->
              new Binder(
                      new MapConfigurationPropertySource(
                          Map.of("axiz.aws.async.connection-ttl", value)))
                  .bind("axiz.aws.async", AxizAwsAsyncTransportProperties.class));
  }
}

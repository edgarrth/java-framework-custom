package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AsyncAwsClientSettingsTest {
  @Test
  void transportReceivesPositiveTypedDurations() {
    assertEquals(Duration.ofMinutes(5), AsyncAwsTransportSettings.defaults().connectionTtl());
    assertEquals(Duration.ofSeconds(30), AsyncAwsTransportSettings.defaults().maxIdleTime());
    for (Duration value :
        new Duration[] {Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(-1)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AsyncAwsTransportSettings(value, Duration.ofSeconds(1)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new AsyncAwsTransportSettings(Duration.ofSeconds(1), value));
    }
    assertEquals(
        Duration.ofMillis(1),
        new AsyncAwsTransportSettings(Duration.ofMillis(1), Duration.ofSeconds(1)).connectionTtl());
  }
}

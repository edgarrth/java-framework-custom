package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AsyncAwsClientSettingsTest {
  @Test
  void connectionLifetimesAreExplicitPositiveAndSupportStandardDurationNotations() {
    assertEquals(
        Duration.ofMinutes(5), AsyncAwsClients.duration(key -> null, "ttl", Duration.ofMinutes(5)));
    for (var entry :
        Map.of(
                "PT0.1S",
                Duration.ofMillis(100),
                "100ms",
                Duration.ofMillis(100),
                "2s",
                Duration.ofSeconds(2),
                "3m",
                Duration.ofMinutes(3),
                "1h",
                Duration.ofHours(1))
            .entrySet()) {
      assertEquals(
          entry.getValue(),
          AsyncAwsClients.duration(key -> entry.getKey(), "ttl", Duration.ofMinutes(5)));
    }
    for (String value :
        new String[] {
          "0ms", "PT0S", "-1s", "PT0.000000001S", "not-a-duration", "999999999999999999999h"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> AsyncAwsClients.duration(key -> value, "ttl", Duration.ofMinutes(5)));
    }
  }
}

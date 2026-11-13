package pe.axiz.adapter.aws.transport;

import java.time.Duration;
import java.util.Objects;

/** Typed native transport configuration; binding belongs to the composition layer. */
public record AsyncAwsTransportSettings(Duration connectionTtl, Duration maxIdleTime) {
  public AsyncAwsTransportSettings {
    positive(connectionTtl);
    positive(maxIdleTime);
  }

  private static void positive(Duration duration) {
    Objects.requireNonNull(duration);
    if (!duration.isPositive() || duration.toMillis() < 1)
      throw new IllegalArgumentException("AWS transport duration must be at least one millisecond");
  }

  public static AsyncAwsTransportSettings defaults() {
    return new AsyncAwsTransportSettings(Duration.ofMinutes(5), Duration.ofSeconds(30));
  }
}

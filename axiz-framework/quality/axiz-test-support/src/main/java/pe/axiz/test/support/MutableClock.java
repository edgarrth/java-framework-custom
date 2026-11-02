package pe.axiz.test.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** Clock that tests can move forward or set. Thread-safe. */
public final class MutableClock extends Clock {

  private volatile Instant now;
  private final ZoneId zone;

  public MutableClock(Instant start, ZoneId zone) {
    this.now = Objects.requireNonNull(start);
    this.zone = Objects.requireNonNull(zone);
  }

  public static MutableClock at(String isoInstant) {
    return new MutableClock(Instant.parse(isoInstant), ZoneOffset.UTC);
  }

  public void set(Instant instant) {
    this.now = Objects.requireNonNull(instant);
  }

  public void advance(Duration duration) {
    this.now = now.plus(duration);
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId newZone) {
    MutableClock c = new MutableClock(now, newZone);
    return c;
  }

  @Override
  public Instant instant() {
    return now;
  }
}

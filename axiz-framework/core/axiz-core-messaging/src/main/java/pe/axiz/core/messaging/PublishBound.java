package pe.axiz.core.messaging;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Caller-visible publication budget, including whether some reachable route has no known bound. */
public record PublishBound(Optional<Duration> known, boolean hasUnbounded) {
  public PublishBound {
    Objects.requireNonNull(known);
    if (known.isEmpty() && !hasUnbounded)
      throw new IllegalArgumentException(
          "A publisher must declare a positive bound or an unknown route");
    known.ifPresent(
        bound -> {
          if (bound.toNanos() <= 0)
            throw new IllegalArgumentException("publish bound must be positive");
        });
  }

  public static PublishBound unbounded() {
    return new PublishBound(Optional.empty(), true);
  }

  public static PublishBound of(Duration max) {
    return new PublishBound(Optional.of(max), false);
  }

  public PublishBound merge(PublishBound other) {
    Optional<Duration> max =
        known.isEmpty()
            ? other.known
            : other.known.isEmpty()
                ? known
                : Optional.of(
                    known.get().compareTo(other.known.get()) >= 0
                        ? known.get()
                        : other.known.get());
    return new PublishBound(max, hasUnbounded || other.hasUnbounded);
  }

  /** The explicit fallback covers unknown routes, never reducing any known bound. */
  public Duration budget(Optional<Duration> fallback) {
    Objects.requireNonNull(fallback);
    fallback.ifPresent(
        value -> {
          if (value.toNanos() <= 0)
            throw new IllegalArgumentException("publish-budget must be positive");
        });
    if (!hasUnbounded) return known.orElseThrow();
    Duration unknown =
        fallback.orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Publisher has unknown routes; declare axiz.outbox.relay.publish-budget"));
    return known.filter(value -> value.compareTo(unknown) > 0).orElse(unknown);
  }
}

package pe.axiz.spring.sequence;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Acota los recursos usados para las reservas de secuencias con independencia de los pools de
 * negocio.
 */
public record SequencePoolPolicy(int maximum, Duration acquireTimeout) {
  public SequencePoolPolicy {
    if (maximum < 1 || maximum > 32 || acquireTimeout.toMillis() < 250)
      throw new IllegalArgumentException(
          "Sequence pool requires max-size 1..32 and acquire-timeout >=250ms");
  }

  public static SequencePoolPolicy from(Environment environment) {
    Binder binder = Binder.get(environment);
    return new SequencePoolPolicy(
        Objects.requireNonNull(binder.bind("axiz.sequence.pool.max-size", Integer.class).orElse(2)),
        Objects.requireNonNull(
            binder
                .bind("axiz.sequence.pool.acquire-timeout", Duration.class)
                .orElse(Duration.ofSeconds(5))));
  }
}

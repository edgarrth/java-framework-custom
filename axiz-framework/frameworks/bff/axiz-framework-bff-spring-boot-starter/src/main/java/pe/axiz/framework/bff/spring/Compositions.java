package pe.axiz.framework.bff.spring;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.Objects;
import pe.axiz.framework.bff.Composer;

/**
 * Creates {@link Composer}s whose parallel tasks carry the caller's context (request metadata,
 * tenant, MDC, trace) through Micrometer context-propagation.
 */
public class Compositions {

  private final ContextSnapshotFactory snapshots;
  private final Duration defaultTimeout;

  public Compositions(ContextSnapshotFactory snapshots, Duration defaultTimeout) {
    this.snapshots = Objects.requireNonNull(snapshots);
    this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
  }

  public Composer compose() {
    return compose(defaultTimeout);
  }

  public Composer compose(Duration timeout) {
    ContextSnapshot snapshot = snapshots.captureAll();
    return Composer.within(timeout, snapshot::wrap);
  }
}

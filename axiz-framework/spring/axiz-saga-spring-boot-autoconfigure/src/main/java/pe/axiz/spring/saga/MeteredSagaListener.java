package pe.axiz.spring.saga;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaStatus;

/**
 * Métricas de sagas: pasos completados, compensaciones, pasos que quedan en duda (alertar sobre
 * {@code axiz.saga.in_doubt}) y sagas terminadas por estado.
 */
public final class MeteredSagaListener implements SagaListener {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "MeterRegistry is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final MeterRegistry registry;

  public MeteredSagaListener(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  @Override
  public void stepCompleted(String sagaName, String sagaId, String step) {
    registry.counter("axiz.saga.steps", "saga", sagaName, "step", step).increment();
  }

  @Override
  public void compensated(String sagaName, String sagaId, String step) {
    registry.counter("axiz.saga.compensations", "saga", sagaName, "step", step).increment();
  }

  @Override
  public void inDoubt(String sagaName, String sagaId, String step) {
    registry.counter("axiz.saga.in_doubt", "saga", sagaName, "step", step).increment();
  }

  @Override
  public void finished(
      String sagaName, String sagaId, SagaStatus status, @Nullable Throwable cause) {
    registry
        .counter(
            "axiz.saga.finished",
            "saga",
            sagaName,
            "status",
            status.name().toLowerCase(Locale.ROOT))
        .increment();
  }
}

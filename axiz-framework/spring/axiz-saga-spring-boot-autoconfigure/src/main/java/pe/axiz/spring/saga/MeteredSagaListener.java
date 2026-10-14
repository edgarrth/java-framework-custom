package pe.axiz.spring.saga;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaStatus;

/**
 * Saga metrics: completed steps, compensations, steps left in doubt (alert on {@code
 * axiz.saga.in_doubt}) and finished sagas by status.
 */
public class MeteredSagaListener implements SagaListener {

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
            status.name().toLowerCase(java.util.Locale.ROOT))
        .increment();
  }
}

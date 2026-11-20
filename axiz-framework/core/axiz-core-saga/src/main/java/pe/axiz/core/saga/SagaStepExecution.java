package pe.axiz.core.saga;

import java.util.Objects;

/**
 * Participant execution identity. The saga and step form a stable operation key; the fence grows
 * across durable transitions and owners, and remains equal for retries of the same attempt.
 * Compensation uses a distinct participant operation from execution. A participant must enforce
 * fences atomically; merely forwarding this value does not fence I/O.
 */
public record SagaStepExecution(String sagaId, String stepName, long fence) {
  public SagaStepExecution {
    Objects.requireNonNull(sagaId);
    Objects.requireNonNull(stepName);
    if (sagaId.isBlank() || stepName.isBlank() || fence < 1)
      throw new IllegalArgumentException("Invalid saga execution identity");
  }
}

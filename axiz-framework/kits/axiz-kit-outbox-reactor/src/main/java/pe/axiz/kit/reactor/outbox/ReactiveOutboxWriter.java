package pe.axiz.kit.reactor.outbox;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.EventEnvelope;
import reactor.core.publisher.Mono;

/**
 * Escritura nativa en frío unida a la transacción de Reactor de quien llama. Las implementaciones
 * deben preservar el contexto del suscriptor, la cancelación y los errores; un puente
 * CompletionStage no puede cumplir este contrato. Ningún driver de base de datos ni implementación
 * de almacén forma parte del puerto.
 */
@FunctionalInterface
public interface ReactiveOutboxWriter {
  /**
   * Identidad del recurso prestado; las implementaciones que no pueden certificarla devuelven null.
   */
  default @Nullable Object transactionResource() {
    return null;
  }

  Mono<Void> write(String destination, EventEnvelope event, @Nullable String aggregateType);
}

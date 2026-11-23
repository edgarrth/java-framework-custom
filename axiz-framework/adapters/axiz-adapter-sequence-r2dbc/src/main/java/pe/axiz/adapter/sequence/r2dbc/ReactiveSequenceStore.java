package pe.axiz.adapter.sequence.r2dbc;

import reactor.core.publisher.Mono;

/**
 * Reserva reactiva nativa de contadores. Un valor exitoso se confirma de forma durable con
 * independencia de la transacción de negocio de quien llama, de modo que los bloques en caché no
 * pueden asignarse otra vez tras un rollback.
 */
@FunctionalInterface
public interface ReactiveSequenceStore {
  Mono<Long> increment(String name, String period, int increment);
}

package pe.axiz.spring.messaging;

import reactor.core.publisher.Mono;

/** Finalización nativa que conserva el contexto de transacción de Reactor circundante. */
public interface ReactiveInboxCompletion {
  Object transactionResource();

  Mono<Boolean> complete(String consumer, String messageKey, String owner);
}

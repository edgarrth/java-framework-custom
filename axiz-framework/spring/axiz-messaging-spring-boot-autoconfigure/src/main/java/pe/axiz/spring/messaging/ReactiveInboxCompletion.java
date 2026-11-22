package pe.axiz.spring.messaging;

import reactor.core.publisher.Mono;

/** Native completion that preserves the surrounding Reactor transaction context. */
public interface ReactiveInboxCompletion {
  Object transactionResource();

  Mono<Boolean> complete(String consumer, String messageKey, String owner);
}

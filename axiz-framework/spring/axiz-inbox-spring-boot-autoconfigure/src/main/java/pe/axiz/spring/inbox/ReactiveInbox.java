package pe.axiz.spring.inbox;

import java.time.Instant;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import reactor.core.publisher.Mono;

/**
 * Fachada de inbox diferida y cancelable para los flujos de la aplicación; los almacenes síncronos
 * atienden a los workers de sondeo.
 */
public interface ReactiveInbox {
  Mono<Acquisition> tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt);

  Mono<Boolean> markProcessed(String consumer, String messageId, String owner, Instant at);

  Mono<Boolean> release(String consumer, String messageId, String owner);

  Mono<Integer> purgeExpired(Instant now);

  Mono<Void> verifySchema();
}

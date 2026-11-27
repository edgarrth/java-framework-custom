package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.reactive.TransactionalOperator;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.messaging.CompletesInTransaction;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.spring.foundation.ReactiveDispatchBoundary;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * Ejecuta un handler reactivo en una única transacción reactiva (sus escrituras R2DBC y los eventos
 * que encola en el outbox hacen commit juntos), con los metadatos de la solicitud y la traza del
 * mensaje consumido en el contexto de Reactor, y lo espera en el hilo del consumidor.
 *
 * @param <T> tipo del payload
 */
public final class ReactiveTransactionalEventHandler<T> implements CompletesInTransaction<T> {

  private final ReactiveTypedEventHandler<T> delegate;
  private final @Nullable TransactionalOperator transaction;
  private final Duration timeout;
  private final @Nullable ReactiveInboxCompletion completion;
  private final List<ReactiveDispatchBoundary> boundaries;

  public ReactiveTransactionalEventHandler(
      ReactiveTypedEventHandler<T> delegate,
      @Nullable TransactionalOperator transaction,
      Duration timeout) {
    this(delegate, transaction, timeout, null);
  }

  public ReactiveTransactionalEventHandler(
      ReactiveTypedEventHandler<T> delegate,
      @Nullable TransactionalOperator transaction,
      Duration timeout,
      @Nullable ReactiveInboxCompletion completion) {
    this(delegate, transaction, timeout, completion, List.of());
  }

  public ReactiveTransactionalEventHandler(
      ReactiveTypedEventHandler<T> delegate,
      @Nullable TransactionalOperator transaction,
      Duration timeout,
      @Nullable ReactiveInboxCompletion completion,
      List<ReactiveDispatchBoundary> boundaries) {
    this.boundaries = List.copyOf(boundaries);
    this.delegate = Objects.requireNonNull(delegate);
    this.transaction = transaction;
    this.timeout = Objects.requireNonNull(timeout);
    this.completion = completion;
    if (completion != null && transaction == null)
      throw new IllegalArgumentException("Atomic inbox completion requires a transaction");
  }

  @Override
  public boolean completesInTransaction() {
    return completion != null;
  }

  @Override
  public void handleClaimed(
      T payload, EventEnvelope envelope, String consumer, String key, String owner)
      throws Exception {
    execute(
        payload,
        envelope,
        Mono.defer(
            () -> {
              var current = completion;
              if (current == null)
                return Mono.error(new IllegalStateException("Inbox completion unavailable"));
              return current
                  .complete(consumer, key, owner)
                  .flatMap(
                      done ->
                          done
                              ? Mono.empty()
                              : Mono.error(
                                  new IllegalStateException("Inbox owner lost its lease")));
            }));
  }

  @Override
  public String eventType() {
    return delegate.eventType();
  }

  @Override
  public Class<T> payloadType() {
    return delegate.payloadType();
  }

  @Override
  public void handle(T payload, EventEnvelope envelope) throws Exception {
    execute(payload, envelope, Mono.empty());
  }

  private void execute(T payload, EventEnvelope envelope, Mono<Void> complete) throws Exception {
    RequestMetadata metadata = RequestContext.get();
    Mono<Void> work = Mono.defer(() -> Mono.from(delegate.react(payload, envelope))).then(complete);
    if (transaction != null) {
      work = transaction.transactional(work);
    }
    for (var boundary : boundaries.reversed()) work = boundary.wrap(work);
    work =
        work.contextWrite(c -> metadata != null ? c.put(RequestContext.KEY, metadata) : c)
            // los thread-locals del consumidor (observación actual, MDC) pasan a ser contexto de
            // Reactor
            .contextCapture();
    try {
      work.block(timeout);
    } catch (RuntimeException e) {
      Throwable cause = Exceptions.unwrap(e);
      if (cause instanceof Exception ex) {
        throw ex;
      }
      throw e;
    }
  }

  public ReactiveTypedEventHandler<T> delegate() {
    return delegate;
  }
}

package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.reactive.TransactionalOperator;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.messaging.CompletesInTransaction;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.spring.foundation.ReactiveDispatchBoundary;
import pe.axiz.spring.foundation.ReactiveInboxCompletion;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * Runs a reactive handler in one reactive transaction (its R2DBC writes and the events it enqueues
 * in the outbox commit together), with the request metadata and the trace of the consumed message
 * in the Reactor context, and waits for it on the consumer thread.
 *
 * @param <T> payload type
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
            // thread-locals of the consumer (current observation, MDC) become Reactor context
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

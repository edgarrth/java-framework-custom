package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.reactive.TransactionalOperator;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * Runs a reactive handler in one reactive transaction (its R2DBC writes and the events it enqueues
 * in the outbox commit together), with the request metadata and the trace of the consumed message
 * in the Reactor context, and waits for it on the consumer thread.
 *
 * @param <T> payload type
 */
public final class ReactiveTransactionalEventHandler<T> implements EventHandler<T> {

  private final ReactiveTypedEventHandler<T> delegate;
  private final @Nullable TransactionalOperator transaction;
  private final Duration timeout;

  public ReactiveTransactionalEventHandler(
      ReactiveTypedEventHandler<T> delegate,
      @Nullable TransactionalOperator transaction,
      Duration timeout) {
    this.delegate = Objects.requireNonNull(delegate);
    this.transaction = transaction;
    this.timeout = Objects.requireNonNull(timeout);
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
    RequestMetadata metadata = RequestContext.get();
    Mono<Void> work = Mono.defer(() -> Mono.from(delegate.react(payload, envelope)));
    if (transaction != null) {
      work = transaction.transactional(work);
    }
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

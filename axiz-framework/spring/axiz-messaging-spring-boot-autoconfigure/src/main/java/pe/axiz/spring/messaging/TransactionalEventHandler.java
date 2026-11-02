package pe.axiz.spring.messaging;

import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;

/**
 * Runs a handler in one transaction, so its writes and the events it enqueues in the outbox commit
 * together or not at all.
 *
 * @param <T> payload type
 */
public final class TransactionalEventHandler<T> implements EventHandler<T> {

  private final EventHandler<T> delegate;
  private final TransactionTemplate transaction;

  public TransactionalEventHandler(EventHandler<T> delegate, TransactionTemplate transaction) {
    this.delegate = Objects.requireNonNull(delegate);
    this.transaction = Objects.requireNonNull(transaction);
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
    try {
      transaction.executeWithoutResult(
          status -> {
            try {
              delegate.handle(payload, envelope);
            } catch (RuntimeException e) {
              throw e;
            } catch (Exception e) {
              throw new Wrapped(e);
            }
          });
    } catch (Wrapped w) {
      throw (Exception) w.getCause();
    }
  }

  public EventHandler<T> delegate() {
    return delegate;
  }

  private static final class Wrapped extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Wrapped(Exception cause) {
      super(cause);
    }
  }
}

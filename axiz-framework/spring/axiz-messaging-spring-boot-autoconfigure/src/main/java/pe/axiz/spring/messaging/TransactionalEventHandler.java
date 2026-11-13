package pe.axiz.spring.messaging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.messaging.CompletesInTransaction;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.spring.foundation.InboxCompletion;

/**
 * Runs a handler in one transaction, so its writes and the events it enqueues in the outbox commit
 * together or not at all.
 *
 * @param <T> payload type
 */
public final class TransactionalEventHandler<T> implements CompletesInTransaction<T> {

  private final EventHandler<T> delegate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "TransactionTemplate is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final TransactionTemplate transaction;

  private final @Nullable InboxCompletion completion;

  public TransactionalEventHandler(EventHandler<T> delegate, TransactionTemplate transaction) {
    this(delegate, transaction, null);
  }

  public TransactionalEventHandler(
      EventHandler<T> delegate,
      TransactionTemplate transaction,
      @Nullable InboxCompletion completion) {
    this.delegate = Objects.requireNonNull(delegate);
    this.transaction = Objects.requireNonNull(transaction);
    this.completion = completion;
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
        () -> {
          InboxCompletion current = completion;
          if (current == null || !current.complete(consumer, key, owner))
            throw new IllegalStateException("Inbox completion failed or the owner lost its lease");
        });
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
    execute(payload, envelope, () -> {});
  }

  private void execute(T payload, EventEnvelope envelope, Runnable complete) throws Exception {
    try {
      transaction.executeWithoutResult(
          status -> {
            try {
              delegate.handle(payload, envelope);
              complete.run();
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

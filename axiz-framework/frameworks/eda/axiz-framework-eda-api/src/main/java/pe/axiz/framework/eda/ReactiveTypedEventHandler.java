package pe.axiz.framework.eda;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;

/**
 * Base class of non-blocking event handlers for reactive services (R2DBC, WebClient): {@link #on}
 * returns a publisher that completes when the event is handled. The consumer thread waits for it
 * (bounded by {@link #timeout()}) so acknowledgement, retries, DLQ and the inbox work exactly as
 * with blocking handlers; the Spring starter runs the publisher in a reactive transaction with the
 * request context and the trace of the message.
 *
 * <pre>
 * &#64;HandlesEvent("pe.axiz.payments.payment-order-created.v1")
 * class ProjectPayment extends ReactiveTypedEventHandler&lt;PaymentOrderCreated&gt; {
 *   protected Publisher&lt;Void&gt; on(PaymentOrderCreated event, EventEnvelope envelope) {
 *     return repository.save(PaymentRow.from(event)).then();
 *   }
 * }
 * </pre>
 *
 * @param <T> payload type
 */
public abstract class ReactiveTypedEventHandler<T> implements EventHandler<T> {

  /** Default bound of one handling. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final String eventType;
  private final Class<T> payloadType;

  protected ReactiveTypedEventHandler() {
    this.eventType = TypedEventHandler.eventTypeOf(getClass());
    this.payloadType =
        TypedEventHandler.resolvePayload(getClass(), ReactiveTypedEventHandler.class);
  }

  /** Handles one event; nothing may run before the publisher is subscribed. */
  protected abstract Publisher<Void> on(T payload, EventEnvelope envelope);

  /** The publisher of one handling, for wrappers that add a transaction or a context. */
  public final Publisher<Void> react(T payload, EventEnvelope envelope) {
    return on(payload, envelope);
  }

  /** Bound of one handling when it is awaited by {@link #handle}. */
  protected Duration timeout() {
    return DEFAULT_TIMEOUT;
  }

  @Override
  public final void handle(T payload, EventEnvelope envelope) throws Exception {
    await(react(payload, envelope), timeout());
  }

  @Override
  public final String eventType() {
    return eventType;
  }

  @Override
  public final Class<T> payloadType() {
    return payloadType;
  }

  /**
   * Waits on the calling (consumer) thread for a publisher to complete; its failure is rethrown,
   * and on timeout the subscription is cancelled.
   */
  public static void await(Publisher<?> publisher, Duration timeout) throws Exception {
    CompletableFuture<@Nullable Void> done = new CompletableFuture<>();
    AtomicReference<@Nullable Subscription> subscription = new AtomicReference<>();
    publisher.subscribe(
        new Subscriber<Object>() {
          @Override
          public void onSubscribe(Subscription s) {
            subscription.set(s);
            s.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(Object value) {
            // values are ignored: completion is the signal
          }

          @Override
          public void onError(Throwable t) {
            done.completeExceptionally(t);
          }

          @Override
          public void onComplete() {
            done.complete(null);
          }
        });
    try {
      done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception ex) {
        throw ex;
      }
      if (cause instanceof Error err) {
        throw err;
      }
      throw e;
    } catch (TimeoutException e) {
      Subscription s = subscription.get();
      if (s != null) {
        s.cancel();
      }
      throw new TimeoutException("Event handling did not complete within " + timeout);
    } catch (InterruptedException e) {
      Subscription s = subscription.get();
      if (s != null) {
        s.cancel();
      }
      Thread.currentThread().interrupt();
      throw e;
    }
  }
}

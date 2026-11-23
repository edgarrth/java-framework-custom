package pe.axiz.spring.messaging;

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
import pe.axiz.core.messaging.TypedEventHandler;

/**
 * Clase base de los handlers de eventos no bloqueantes para servicios reactivos (R2DBC, WebClient):
 * {@link #on} devuelve un publicador que completa cuando se ha manejado el evento. El hilo del
 * consumidor lo espera (acotado por {@link #timeout()}) para que la confirmación, los reintentos,
 * la DLQ y el inbox funcionen exactamente igual que con handlers bloqueantes; el starter de Spring
 * ejecuta el publicador en una transacción reactiva con el contexto de la solicitud y la traza del
 * mensaje.
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
 * @param <T> tipo del payload
 */
public abstract class ReactiveTypedEventHandler<T> implements EventHandler<T> {

  /** Límite por defecto de un manejo. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final String eventType;
  private final Class<T> payloadType;

  protected ReactiveTypedEventHandler() {
    this.eventType = TypedEventHandler.eventTypeOf(getClass());
    this.payloadType =
        TypedEventHandler.resolvePayload(getClass(), ReactiveTypedEventHandler.class);
  }

  /** Maneja un evento; nada puede ejecutarse antes de que se suscriba el publicador. */
  protected abstract Publisher<Void> on(T payload, EventEnvelope envelope);

  /** El publicador de un manejo, para los wrappers que añaden una transacción o un contexto. */
  public final Publisher<Void> react(T payload, EventEnvelope envelope) {
    return on(payload, envelope);
  }

  /** Límite de un manejo cuando lo espera {@link #handle}. */
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
   * Espera en el hilo invocante (del consumidor) a que un publicador complete; su fallo se relanza
   * y, ante un timeout, se cancela la suscripción.
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
            // los valores se ignoran: la finalización es la señal
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

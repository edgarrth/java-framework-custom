package pe.axiz.core.messaging;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Base class of event handlers: the event type comes from {@link HandlesEvent} and the payload type
 * from the generic argument, so subclasses only implement {@link #on}. Handlers must be idempotent;
 * the inbox removes duplicates and they run in one transaction with the outbox.
 *
 * @param <T> payload type
 */
public abstract class TypedEventHandler<T> implements EventHandler<T> {

  private final String eventType;
  private final Class<T> payloadType;

  protected TypedEventHandler() {
    this.eventType = eventTypeOf(getClass());
    this.payloadType = resolvePayload(getClass(), TypedEventHandler.class);
  }

  /** Handles one event. */
  protected abstract void on(T payload, EventEnvelope envelope) throws Exception;

  @Override
  public final void handle(T payload, EventEnvelope envelope) throws Exception {
    on(payload, envelope);
  }

  @Override
  public final String eventType() {
    return eventType;
  }

  @Override
  public final Class<T> payloadType() {
    return payloadType;
  }

  /** Resolves the inherited event annotation for handler implementations. */
  public static String eventTypeOf(Class<?> type) {
    HandlesEvent annotation = type.getAnnotation(HandlesEvent.class);
    if (annotation == null) {
      throw new IllegalStateException(type.getName() + " must be annotated with @HandlesEvent");
    }
    return annotation.value();
  }

  /** Resolves the concrete payload of a typed handler base class. */
  @SuppressWarnings("unchecked")
  public static <T> Class<T> resolvePayload(Class<?> type, Class<?> base) {
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      Type sup = c.getGenericSuperclass();
      if (sup instanceof ParameterizedType p && p.getRawType() == base) {
        Type arg = p.getActualTypeArguments()[0];
        if (arg instanceof Class<?> k) {
          return (Class<T>) k;
        }
        if (arg instanceof ParameterizedType pk && pk.getRawType() instanceof Class<?> raw) {
          return (Class<T>) raw;
        }
      }
    }
    throw new IllegalStateException("Cannot resolve the payload type of " + type.getName());
  }
}

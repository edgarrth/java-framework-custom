package pe.axiz.core.messaging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Clase base de los handlers de eventos: el tipo de evento proviene de {@link HandlesEvent} y el
 * tipo del payload del argumento genérico, de modo que las subclases solo implementan {@link #on}.
 * Los handlers deben ser idempotentes; el inbox elimina duplicados y corren en una transacción
 * junto con el outbox.
 *
 * @param <T> tipo del payload
 */
public abstract class TypedEventHandler<T> implements EventHandler<T> {

  private final String eventType;
  private final Class<T> payloadType;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Abstract application extension point validates handler metadata at construction; "
              + "subclasses are trusted application code and no finalizer is used.")
  protected TypedEventHandler() {
    this.eventType = eventTypeOf(getClass());
    this.payloadType = resolvePayload(getClass(), TypedEventHandler.class);
  }

  /** Maneja un evento. */
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

  /** Resuelve la anotación de evento heredada para las implementaciones de handlers. */
  public static String eventTypeOf(Class<?> type) {
    HandlesEvent annotation = type.getAnnotation(HandlesEvent.class);
    if (annotation == null) {
      throw new IllegalStateException(type.getName() + " must be annotated with @HandlesEvent");
    }
    return annotation.value();
  }

  /** Resuelve el payload concreto de una clase base de handler tipado. */
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

package pe.axiz.core.messaging;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.errors.Failures;

/**
 * Encamina envelopes a los handlers por tipo, decodificando el payload y deduplicando mediante el
 * inbox. Cada resultado es explícito: ACK (procesado, ya procesado o tipo desconocido cuando se
 * ignora), RETRY (fallo del handler o del inbox), DEFER (otra entrega tiene el mensaje) o
 * DEAD_LETTER. Que falte un handler para un tipo suscrito es un error de arranque, no una sorpresa
 * en ejecución.
 */
public final class EventDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

  private final String consumer;
  private final Map<String, EventHandler<?>> handlers;
  private final PayloadCodec codec;
  private final Deduplicator deduplicator;
  private final ConsumerRetryPolicy retryPolicy;
  private final boolean ignoreUnknownTypes;

  public EventDispatcher(
      String consumer,
      Collection<EventHandler<?>> handlers,
      PayloadCodec codec,
      Deduplicator deduplicator,
      ConsumerRetryPolicy retryPolicy,
      boolean ignoreUnknownTypes) {
    this.consumer = Objects.requireNonNull(consumer);
    Map<String, EventHandler<?>> map = new LinkedHashMap<>();
    for (EventHandler<?> h : handlers) {
      if (map.put(h.eventType(), h) != null) {
        throw new IllegalStateException("Two handlers for event type " + h.eventType());
      }
    }
    this.handlers = Map.copyOf(map);
    this.codec = Objects.requireNonNull(codec);
    this.deduplicator = Objects.requireNonNull(deduplicator);
    this.retryPolicy = Objects.requireNonNull(retryPolicy);
    this.ignoreUnknownTypes = ignoreUnknownTypes;
  }

  /** Falla al arrancar cuando un tipo suscrito no tiene handler. */
  public void verifySubscriptions(Collection<String> subscribedTypes) {
    for (String t : subscribedTypes) {
      if (!handlers.containsKey(t)) {
        throw new IllegalStateException(
            "No EventHandler registered for subscribed event type " + t);
      }
    }
  }

  /**
   * Despacha una entrega.
   *
   * @param envelope evento recibido
   * @param attempt intento de entrega (desde 1, según el conteo de recepciones del broker)
   * @return qué hacer con el mensaje
   */
  public DeliveryDecision dispatch(EventEnvelope envelope, int attempt) {
    return dispatchWithDelay(envelope, attempt).decision();
  }

  /**
   * Decisión y, para {@link DeliveryDecision#DEFER}, cuánto esperar antes de reentregar.
   *
   * @param decision qué hacer
   * @param retryAfter retraso sugerido (cero cuando no se difiere)
   * @param failure descripción apta para logs del fallo detrás de un RETRY o DEAD_LETTER ({@link
   *     Failures#describe}), para transportes que registran por qué un mensaje fue a dead-letter;
   *     null en otro caso
   */
  public record Outcome(DeliveryDecision decision, Duration retryAfter, @Nullable String failure) {

    public Outcome(DeliveryDecision decision, Duration retryAfter) {
      this(decision, retryAfter, null);
    }

    static Outcome of(DeliveryDecision decision) {
      return new Outcome(decision, Duration.ZERO);
    }

    static Outcome failed(DeliveryDecision decision, String failure) {
      return new Outcome(decision, Duration.ZERO, failure);
    }
  }

  /** Como {@link #dispatch}, con el retraso de las entregas diferidas. */
  public Outcome dispatchWithDelay(EventEnvelope envelope, int attempt) {
    EventHandler<?> handler = handlers.get(envelope.type());
    if (handler == null) {
      if (ignoreUnknownTypes) {
        LOG.debug("Ignoring event type {} (no handler)", envelope.type());
        return Outcome.of(DeliveryDecision.ACK);
      }
      LOG.warn("No handler for event type {}; dead-lettering {}", envelope.type(), envelope.id());
      return Outcome.failed(
          DeliveryDecision.DEAD_LETTER, "no handler for event type " + envelope.type());
    }
    // la identidad de CloudEvents es source + id: el mismo id de otro productor es otro evento
    String key = MessageKeys.of(envelope);
    Deduplicator.Claim claim;
    try {
      claim = deduplicator.claim(consumer, key);
    } catch (RuntimeException e) {
      // el inbox no está disponible: el mensaje está bien, se reintenta más tarde
      LOG.warn("Inbox unavailable for event {}: {}", envelope.id(), Failures.describe(e));
      return Outcome.of(DeliveryDecision.RETRY);
    }
    // exhaustivo: un nuevo estado de claim debe decidir aquí qué pasa con la entrega
    @Nullable Outcome early =
        switch (claim.status()) {
          case ALREADY_PROCESSED -> {
            LOG.info("Duplicate event {} ignored by {}", envelope.id(), consumer);
            yield Outcome.of(DeliveryDecision.ACK);
          }
          case BUSY -> {
            LOG.info(
                "Event {} is being processed by another delivery of {}; deferring",
                envelope.id(),
                consumer);
            yield new Outcome(DeliveryDecision.DEFER, claim.retryAfter());
          }
          case ACQUIRED -> null; // se procesa más abajo
        };
    if (early != null) {
      return early;
    }
    try {
      invoke(handler, envelope, key, claim.token());
    } catch (Exception e) {
      release(envelope, key, claim);
      DeliveryDecision decision = retryPolicy.onFailure(e, attempt);
      String failure = Failures.describe(e);
      LOG.warn(
          "Event {} type {} failed on attempt {}: {} -> {}",
          envelope.id(),
          envelope.type(),
          attempt,
          failure,
          decision);
      return Outcome.failed(decision, failure);
    }
    if (handler instanceof CompletesInTransaction<?> atomic && atomic.completesInTransaction())
      return Outcome.of(DeliveryDecision.ACK);
    try {
      if (!deduplicator.completed(consumer, key, claim.token())) {
        LOG.warn(
            "Event {} processed after its inbox lease was taken over by another delivery of {}; "
                + "handlers must stay idempotent",
            envelope.id(),
            consumer);
      }
    } catch (RuntimeException e) {
      // los efectos ocurrieron: se confirma; el registro expira con su retención
      LOG.warn("Could not mark event {} processed: {}", envelope.id(), Failures.describe(e));
    }
    return Outcome.of(DeliveryDecision.ACK);
  }

  private void release(EventEnvelope envelope, String key, Deduplicator.Claim claim) {
    try {
      deduplicator.failed(consumer, key, claim.token());
    } catch (RuntimeException e) {
      // el lease expira por sí solo; luego una reentrega lo toma
      LOG.warn(
          "Could not release inbox claim of event {}: {}", envelope.id(), Failures.describe(e));
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void invoke(EventHandler<T> handler, EventEnvelope envelope, String key, String owner)
      throws Exception {
    T payload;
    try {
      payload = codec.decode(envelope.data(), handler.payloadType());
    } catch (RuntimeException e) {
      throw new PayloadException("Cannot decode payload of " + envelope.type(), e);
    }
    if (handler instanceof CompletesInTransaction<?> atomic && atomic.completesInTransaction())
      ((CompletesInTransaction<T>) atomic).handleClaimed(payload, envelope, consumer, key, owner);
    else handler.handle(payload, envelope);
  }
}

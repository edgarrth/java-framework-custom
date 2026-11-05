package pe.axiz.core.messaging;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes envelopes to handlers by type, decoding the payload and deduplicating through the inbox.
 * Every outcome is explicit: ACK (processed, already processed or unknown type when ignoring),
 * RETRY (handler or inbox failure), DEFER (another delivery holds the message) or DEAD_LETTER. A
 * missing handler for a subscribed type is a startup error, not a runtime surprise.
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

  /** Fails at startup when a subscribed type has no handler. */
  public void verifySubscriptions(Collection<String> subscribedTypes) {
    for (String t : subscribedTypes) {
      if (!handlers.containsKey(t)) {
        throw new IllegalStateException(
            "No EventHandler registered for subscribed event type " + t);
      }
    }
  }

  /**
   * Dispatches one delivery.
   *
   * @param envelope received event
   * @param attempt delivery attempt (1-based, from the broker receive count)
   * @return what to do with the message
   */
  public DeliveryDecision dispatch(EventEnvelope envelope, int attempt) {
    return dispatchWithDelay(envelope, attempt).decision();
  }

  /**
   * A decision and, for {@link DeliveryDecision#DEFER}, how long to wait before redelivering.
   *
   * @param decision what to do
   * @param retryAfter suggested delay (zero when not deferred)
   */
  public record Outcome(DeliveryDecision decision, Duration retryAfter) {
    static Outcome of(DeliveryDecision decision) {
      return new Outcome(decision, Duration.ZERO);
    }
  }

  /** Like {@link #dispatch} with the delay of deferred deliveries. */
  public Outcome dispatchWithDelay(EventEnvelope envelope, int attempt) {
    EventHandler<?> handler = handlers.get(envelope.type());
    if (handler == null) {
      if (ignoreUnknownTypes) {
        LOG.debug("Ignoring event type {} (no handler)", envelope.type());
        return Outcome.of(DeliveryDecision.ACK);
      }
      LOG.warn("No handler for event type {}; dead-lettering {}", envelope.type(), envelope.id());
      return Outcome.of(DeliveryDecision.DEAD_LETTER);
    }
    // CloudEvents identity is source + id: the same id from another producer is another event
    String key = MessageKeys.of(envelope);
    Deduplicator.Claim claim;
    try {
      claim = deduplicator.claim(consumer, key);
    } catch (RuntimeException e) {
      // the inbox is unavailable: the message is fine, try again later
      LOG.warn("Inbox unavailable for event {}: {}", envelope.id(), e.toString());
      return Outcome.of(DeliveryDecision.RETRY);
    }
    // exhaustive: a new claim status must decide here what happens to the delivery
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
          case ACQUIRED -> null; // processed below
        };
    if (early != null) {
      return early;
    }
    try {
      invoke(handler, envelope, key, claim.token());
    } catch (Exception e) {
      release(envelope, key, claim);
      DeliveryDecision decision = retryPolicy.onFailure(e, attempt);
      LOG.warn(
          "Event {} type {} failed on attempt {}: {} -> {}",
          envelope.id(),
          envelope.type(),
          attempt,
          e.toString(),
          decision);
      return Outcome.of(decision);
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
      // the effects happened: acknowledge; the registration expires with its retention
      LOG.warn("Could not mark event {} processed: {}", envelope.id(), e.toString());
    }
    return Outcome.of(DeliveryDecision.ACK);
  }

  private void release(EventEnvelope envelope, String key, Deduplicator.Claim claim) {
    try {
      deduplicator.failed(consumer, key, claim.token());
    } catch (RuntimeException e) {
      // the lease expires on its own; a redelivery then takes it over
      LOG.warn("Could not release inbox claim of event {}: {}", envelope.id(), e.toString());
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

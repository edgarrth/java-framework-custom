package pe.axiz.core.outbox;

import java.util.function.Consumer;
import pe.axiz.core.messaging.EventEnvelope;

/**
 * Keeps the trace of the caller with each event written to the outbox. The relay publishes the
 * event later, outside the request: with the trace context stored in the event ({@link
 * EventEnvelope#OUTBOX_TRACEPARENT}) the publication and its consumers continue the trace of the
 * request instead of starting one of their own.
 */
@FunctionalInterface
public interface OutboxWriteTracing {

  /** No tracing: the event is stored as it is. */
  OutboxWriteTracing NONE = (destination, event, write) -> write.accept(event);

  /**
   * Stores the event through {@code write}, after adding the trace context of the caller.
   *
   * @param destination logical destination
   * @param event event to store
   * @param write stores the (possibly stamped) event
   */
  void traced(String destination, EventEnvelope event, Consumer<EventEnvelope> write);
}

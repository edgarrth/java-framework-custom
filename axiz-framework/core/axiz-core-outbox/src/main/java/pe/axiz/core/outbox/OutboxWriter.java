package pe.axiz.core.outbox;

import java.time.Clock;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;

/**
 * Registers events in the outbox within the caller's transaction. The event is published later by
 * the relay, so it is published if and only if the business transaction commits.
 */
public final class OutboxWriter {

  private final OutboxStore store;
  private final Clock clock;

  public OutboxWriter(OutboxStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public void write(String destination, EventEnvelope event, @Nullable String aggregateType) {
    var now = clock.instant();
    store.append(
        new OutboxRecord(
            event.id(),
            destination,
            aggregateType,
            event.subject(),
            event.type(),
            CloudEventJson.write(event),
            OutboxRecord.Status.PENDING,
            0,
            now,
            now,
            null));
  }
}

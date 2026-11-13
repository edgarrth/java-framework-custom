package pe.axiz.core.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "OutboxStore is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final OutboxStore store;

  private final Clock clock;
  private final OutboxWriteTracing tracing;

  public OutboxWriter(OutboxStore store, Clock clock) {
    this(store, clock, OutboxWriteTracing.NONE);
  }

  public OutboxWriter(OutboxStore store, Clock clock, OutboxWriteTracing tracing) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
    this.tracing = Objects.requireNonNull(tracing);
  }

  /** Stores the event (with the trace context of the caller, see {@link OutboxWriteTracing}). */
  public void write(String destination, EventEnvelope event, @Nullable String aggregateType) {
    tracing.traced(
        destination,
        event,
        stamped -> {
          var now = clock.instant();
          store.append(
              new OutboxRecord(
                  stamped.id(),
                  destination,
                  aggregateType,
                  stamped.subject(),
                  stamped.type(),
                  CloudEventJson.write(stamped),
                  OutboxRecord.Status.PENDING,
                  0,
                  now,
                  now,
                  null));
        });
  }
}

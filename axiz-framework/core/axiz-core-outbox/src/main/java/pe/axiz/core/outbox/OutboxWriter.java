package pe.axiz.core.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.PayloadProtector;
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

  private final PayloadProtector protector;
  private final Clock clock;
  private final OutboxWriteTracing tracing;

  public OutboxWriter(OutboxStore store, Clock clock) {
    this(store, clock, OutboxWriteTracing.NONE);
  }

  public OutboxWriter(OutboxStore store, Clock clock, OutboxWriteTracing tracing) {
    this(store, clock, tracing, PayloadProtector.NONE);
  }

  public OutboxWriter(
      OutboxStore store, Clock clock, OutboxWriteTracing tracing, PayloadProtector protector) {
    this.protector = Objects.requireNonNull(protector);
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
    this.tracing = Objects.requireNonNull(tracing);
  }

  /** Identity of the borrowed transaction resource; null when the store cannot attest it. */
  public @Nullable Object transactionResource() {
    return store.transactionResource();
  }

  /** Stores the event (with the trace context of the caller, see {@link OutboxWriteTracing}). */
  public void write(String destination, EventEnvelope event, @Nullable String aggregateType) {
    tracing.traced(
        destination,
        event,
        stamped -> {
          var now = clock.instant();
          store.append(
              OutboxRecord.builder(
                      stamped.id(),
                      destination,
                      stamped.type(),
                      protector.protect(
                          CloudEventJson.write(stamped),
                          "purpose:outbox:" + destination,
                          stamped.id()),
                      now)
                  .aggregate(aggregateType, stamped.subject())
                  .status(OutboxRecord.Status.PENDING)
                  .attempts(0)
                  .nextAttemptAt(now)
                  .lastError(null)
                  .aggregateSequence(0)
                  .lease(null, null)
                  .build());
        });
  }
}

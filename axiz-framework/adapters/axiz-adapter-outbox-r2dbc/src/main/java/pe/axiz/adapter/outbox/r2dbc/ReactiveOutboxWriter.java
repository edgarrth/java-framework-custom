package pe.axiz.adapter.outbox.r2dbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.outbox.OutboxRecord;
import reactor.core.publisher.Mono;

/**
 * Registers events in the outbox inside the caller's reactive transaction: they are published if
 * and only if that transaction commits.
 */
public final class ReactiveOutboxWriter {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "R2dbcOutboxStore is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final R2dbcOutboxStore store;

  private final Clock clock;

  public ReactiveOutboxWriter(R2dbcOutboxStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public Mono<Void> write(String destination, EventEnvelope event, @Nullable String aggregateType) {
    return Mono.defer(
        () -> {
          var now = clock.instant();
          return store.insert(
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
        });
  }
}

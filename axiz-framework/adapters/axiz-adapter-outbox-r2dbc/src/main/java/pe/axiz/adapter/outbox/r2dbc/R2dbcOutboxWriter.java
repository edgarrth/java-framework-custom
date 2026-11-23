package pe.axiz.adapter.outbox.r2dbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import reactor.core.publisher.Mono;

/**
 * Registra eventos en el outbox dentro de la transacción reactiva de quien llama: se publican si y
 * solo si esa transacción hace commit.
 */
public final class R2dbcOutboxWriter implements ReactiveOutboxWriter {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "R2dbcOutboxStore is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final R2dbcOutboxStore store;

  private final Clock clock;
  private final AsyncPayloadProtector protector;

  public R2dbcOutboxWriter(R2dbcOutboxStore store, Clock clock) {
    this(store, clock, AsyncPayloadProtector.NONE);
  }

  public R2dbcOutboxWriter(R2dbcOutboxStore store, Clock clock, AsyncPayloadProtector protector) {
    this.protector = Objects.requireNonNull(protector);
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public @Nullable Object transactionResource() {
    return store.transactionResource();
  }

  @Override
  public Mono<Void> write(String destination, EventEnvelope event, @Nullable String aggregateType) {
    return Mono.defer(
        () -> {
          var now = clock.instant();
          return Mono.fromCompletionStage(
                  () ->
                      protector.protect(
                          CloudEventJson.write(event), "purpose:outbox:" + destination, event.id()))
              .flatMap(
                  stored ->
                      store.insert(
                          OutboxRecord.builder(event.id(), destination, event.type(), stored, now)
                              .aggregate(aggregateType, event.subject())
                              .status(OutboxRecord.Status.PENDING)
                              .attempts(0)
                              .nextAttemptAt(now)
                              .lastError(null)
                              .aggregateSequence(0)
                              .lease(null, null)
                              .build()));
        });
  }
}

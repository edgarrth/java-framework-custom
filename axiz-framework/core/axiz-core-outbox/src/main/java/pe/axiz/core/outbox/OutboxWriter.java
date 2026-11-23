package pe.axiz.core.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;

/**
 * Registra eventos en el outbox dentro de la transacción de quien llama. El relay publica el evento
 * más tarde, de modo que se publica si y solo si la transacción de negocio hace commit.
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

  /**
   * Identidad del recurso de transacción prestado; null cuando el almacén no puede certificarla.
   */
  public @Nullable Object transactionResource() {
    return store.transactionResource();
  }

  /**
   * Almacena el evento (con el contexto de traza de quien llama, ver {@link OutboxWriteTracing}).
   */
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

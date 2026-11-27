package pe.axiz.spring.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.outbox.OutboxWriter;

/**
 * Registra eventos en el outbox dentro de la transacción del invocante; el relay los publica
 * después del commit. Invocarlo fuera de una transacción es un error, porque el evento podría
 * almacenarse sin el cambio de negocio (o al revés). El writer conserva la traza del invocante
 * junto con el evento ({@link pe.axiz.core.outbox.OutboxWriteTracing}).
 */
public class TransactionalEvents {

  private final EventFactory factory;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "OutboxWriter is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final OutboxWriter writer;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public TransactionalEvents(EventFactory factory, OutboxWriter writer) {
    this.factory = Objects.requireNonNull(factory);
    this.writer = Objects.requireNonNull(writer);
  }

  /**
   * Encola un evento.
   *
   * @param destination destino lógico
   * @param eventId id del evento de dominio (se convierte en el id de CloudEvents y en el id de
   *     deduplicación FIFO)
   * @param type tipo de evento sin prefijo
   * @param aggregateType tipo de agregado, p. ej. {@code payment}
   * @param aggregateId id del agregado (subject de CloudEvents y clave de partición)
   * @param payload payload
   * @return el sobre almacenado
   */
  public EventEnvelope enqueue(
      String destination,
      @Nullable String eventId,
      String type,
      String aggregateType,
      @Nullable String aggregateId,
      Object payload) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox events must be enqueued inside a transaction");
    }
    EventEnvelope envelope =
        factory.create(eventId, type, aggregateId, payload, RequestContext.get());
    writer.write(destination, envelope, aggregateType);
    return envelope;
  }
}

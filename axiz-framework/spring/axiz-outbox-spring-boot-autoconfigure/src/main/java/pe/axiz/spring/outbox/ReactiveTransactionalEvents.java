package pe.axiz.spring.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import reactor.core.publisher.Mono;

/**
 * Contraparte reactiva de {@link TransactionalEvents} para servicios R2DBC: el evento se inserta en
 * el outbox con la misma conexión y transacción que el cambio de negocio ({@code
 * TransactionalOperator} o {@code @Transactional} en un método que devuelve {@code Mono}/{@code
 * Flux}); el relay lo publica después del commit. Suscribirse fuera de una transacción reactiva
 * falla. La escritura conserva la traza del invocante (la observación en el contexto de Reactor),
 * como {@link TransactionalEvents}.
 */
public class ReactiveTransactionalEvents {

  static final String NO_TRANSACTION = "Outbox events must be enqueued inside a transaction";

  private final EventFactory factory;

  /**
   * Clave de la observación actual en el contexto de Reactor (propagación de contexto de
   * Micrometer).
   */
  static final String OBSERVATION_KEY = "micrometer.observation";

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ReactiveOutboxWriter is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final ReactiveOutboxWriter writer;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObservationRegistry is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final ObservationRegistry observations;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public ReactiveTransactionalEvents(EventFactory factory, ReactiveOutboxWriter writer) {
    this(factory, writer, ObservationRegistry.NOOP);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public ReactiveTransactionalEvents(
      EventFactory factory, ReactiveOutboxWriter writer, ObservationRegistry observations) {
    this.factory = Objects.requireNonNull(factory);
    this.writer = Objects.requireNonNull(writer);
    this.observations = Objects.requireNonNull(observations);
  }

  /**
   * Encola un evento; no ocurre nada hasta que el {@code Mono} devuelto se suscribe dentro de la
   * transacción.
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
  public Mono<EventEnvelope> enqueue(
      String destination,
      @Nullable String eventId,
      String type,
      String aggregateType,
      @Nullable String aggregateId,
      Object payload) {
    return TransactionSynchronizationManager.forCurrentTransaction()
        .onErrorMap(NoTransactionException.class, e -> new IllegalStateException(NO_TRANSACTION, e))
        .filter(TransactionSynchronizationManager::isActualTransactionActive)
        .switchIfEmpty(Mono.error(() -> new IllegalStateException(NO_TRANSACTION)))
        .flatMap(
            tx ->
                Mono.deferContextual(
                    ctx -> {
                      RequestMetadata fromContext = ctx.getOrDefault(RequestContext.KEY, null);
                      EventEnvelope created =
                          factory.create(
                              eventId,
                              type,
                              aggregateId,
                              payload,
                              fromContext != null ? fromContext : RequestContext.get());
                      Observation parent = ctx.getOrDefault(OBSERVATION_KEY, null);
                      OutboxTrace.Write write =
                          OutboxTrace.start(observations, destination, created, parent);
                      EventEnvelope envelope = write.stamp(created);
                      return writer
                          .write(destination, envelope, aggregateType)
                          .doOnError(write.observation()::error)
                          .doFinally(signal -> write.observation().stop())
                          .thenReturn(envelope);
                    }));
  }
}

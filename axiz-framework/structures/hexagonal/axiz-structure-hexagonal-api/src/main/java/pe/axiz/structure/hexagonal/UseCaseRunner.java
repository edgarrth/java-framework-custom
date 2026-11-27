package pe.axiz.structure.hexagonal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.domain.DomainEvent;

/**
 * Ejecuta un caso de uso: una transacción, con los eventos de dominio recolectados durante su
 * ejecución y entregados al sink dentro de la transacción (outbox) o después del commit
 * (publicación directa). Los casos de uso anidados se unen al externo y sus eventos se publican con
 * él.
 */
public final class UseCaseRunner {

  private final UseCaseTransaction transaction;
  private final DomainEventSink sink;

  public UseCaseRunner(UseCaseTransaction transaction, DomainEventSink sink) {
    this.transaction = Objects.requireNonNull(transaction);
    this.sink = Objects.requireNonNull(sink);
  }

  public <T> T run(boolean readOnly, Supplier<T> work) {
    @Nullable List<DomainEvent> previous = DomainEvents.begin();
    try {
      AtomicReference<List<DomainEvent>> afterCommit = new AtomicReference<>(List.of());
      T result =
          transaction.execute(
              readOnly,
              () -> {
                T value = work.get();
                List<DomainEvent> events = DomainEvents.drain();
                if (previous != null) {
                  previous.addAll(events);
                } else if (sink.transactional()) {
                  if (!events.isEmpty()) {
                    sink.publish(events);
                  }
                } else {
                  afterCommit.set(events);
                }
                return value;
              });
      if (!afterCommit.get().isEmpty()) {
        try {
          sink.publish(afterCommit.get());
        } catch (RuntimeException publicationFailure) {
          System.getLogger(UseCaseRunner.class.getName())
              .log(
                  System.Logger.Level.ERROR,
                  "Business transaction committed; direct event publication requires reconciliation; "
                      + "eventCount={0}, failureType={1}. Do not retry the business operation.",
                  afterCommit.get().size(),
                  publicationFailure.getClass().getName());
          throw new PostCommitPublicationException();
        }
      }
      return result;
    } finally {
      DomainEvents.end(previous);
    }
  }

  public <I, O> O run(UseCase<I, O> useCase, I input) {
    return run(false, () -> useCase.execute(input));
  }
}

package pe.axiz.framework.hexagonal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.domain.DomainEvent;

/**
 * Runs a use case: one transaction, domain events collected while it runs and handed to the sink
 * inside the transaction (outbox) or after commit (direct publication). Nested use cases join the
 * outer one and their events are published with it.
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
        sink.publish(afterCommit.get());
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

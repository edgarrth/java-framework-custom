package pe.axiz.core.audit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;

/**
 * Delivers to each sink in order without waiting on a thread. Every sink is attempted after a
 * failure; acceptance fails if any sink fails. Cancellation stops the active and later deliveries.
 */
public final class CompositeAsyncAuditPublisher implements AsyncAuditEventPublisher {
  private final List<AsyncAuditEventPublisher> delegates;

  public CompositeAsyncAuditPublisher(List<AsyncAuditEventPublisher> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
    AtomicReference<@Nullable Throwable> first = new AtomicReference<>();
    CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
    for (AsyncAuditEventPublisher delegate : delegates) {
      chain =
          Stages.compose(
              chain,
              ignored ->
                  Stages.settle(
                      Stages.call(() -> delegate.publish(event)),
                      failure -> first.compareAndSet(null, failure)));
    }
    return Stages.compose(
        chain,
        ignored -> {
          Throwable failure = first.get();
          return failure == null
              ? CompletableFuture.completedFuture(null)
              : CompletableFuture.failedFuture(failure);
        });
  }
}

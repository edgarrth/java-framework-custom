package pe.axiz.core.messaging;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;

/**
 * Selects the first native publisher supporting a destination. A failed selected publisher never
 * triggers delivery to another broker; that could duplicate an event. Cancellation reaches its
 * stage.
 */
public final class RoutingAsyncEventPublisher implements AsyncEventPublisher {
  private final List<AsyncEventPublisher> delegates;

  public RoutingAsyncEventPublisher(List<AsyncEventPublisher> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public CompletionStage<PublishResult> publish(String destination, EventEnvelope event) {
    return Stages.call(
        () -> {
          for (AsyncEventPublisher publisher : delegates) {
            if (publisher.supports(destination)) return publisher.publish(destination, event);
          }
          return CompletableFuture.failedFuture(
              new IllegalArgumentException(
                  "No native publisher supports the requested destination"));
        });
  }

  @Override
  public boolean supports(String destination) {
    return delegates.stream().anyMatch(p -> p.supports(destination));
  }
}

package pe.axiz.test.support;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;

/**
 * Test boundary only: applies the same wire contract to synchronous and native asynchronous
 * publishers.
 */
public final class AsyncPublisherProbe implements EventPublisher {
  private final AsyncEventPublisher publisher;

  public AsyncPublisherProbe(AsyncEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public boolean supports(String destination) {
    return publisher.supports(destination);
  }

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    try {
      return publisher.publish(destination, event).toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted publisher contract", interrupted);
    } catch (TimeoutException timeout) {
      throw new AssertionError("Publisher never completed", timeout);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new AssertionError("Publisher failed", failure);
    }
  }
}

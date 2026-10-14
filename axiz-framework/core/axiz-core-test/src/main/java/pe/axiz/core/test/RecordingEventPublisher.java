package pe.axiz.core.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;

/** Publisher that records events; can be switched to fail to test retries. */
public final class RecordingEventPublisher implements EventPublisher {

  /**
   * A recorded publication.
   *
   * @param destination destination
   * @param event event
   */
  public record Published(String destination, EventEnvelope event) {}

  private final List<Published> published = new CopyOnWriteArrayList<>();
  private volatile boolean failing;

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    if (failing) {
      throw new PublishException("simulated broker failure", new IllegalStateException("failing"));
    }
    published.add(new Published(destination, event));
    return new PublishResult(event.id(), destination, Integer.toString(published.size()));
  }

  public List<Published> published() {
    return List.copyOf(published);
  }

  public List<EventEnvelope> eventsOfType(String type) {
    return published.stream().map(Published::event).filter(e -> e.type().equals(type)).toList();
  }

  public void failing(boolean value) {
    this.failing = value;
  }

  public void clear() {
    published.clear();
  }
}

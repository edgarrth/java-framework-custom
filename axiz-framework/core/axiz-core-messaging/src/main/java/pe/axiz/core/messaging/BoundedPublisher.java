package pe.axiz.core.messaging;

/** Optional publication-budget contract, shared by adapters, routing and instrumentation. */
public interface BoundedPublisher {
  PublishBound publishBound();

  static PublishBound of(EventPublisher publisher) {
    return publisher instanceof BoundedPublisher bounded
        ? bounded.publishBound()
        : PublishBound.unbounded();
  }
}

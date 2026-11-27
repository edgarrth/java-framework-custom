package pe.axiz.core.messaging;

/**
 * Contrato opcional de presupuesto de publicación, compartido por adaptadores, enrutamiento e
 * instrumentación.
 */
public interface BoundedPublisher {
  PublishBound publishBound();

  static PublishBound of(EventPublisher publisher) {
    return publisher instanceof BoundedPublisher bounded
        ? bounded.publishBound()
        : PublishBound.unbounded();
  }
}

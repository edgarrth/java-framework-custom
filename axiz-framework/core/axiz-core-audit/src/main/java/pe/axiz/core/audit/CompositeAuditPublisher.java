package pe.axiz.core.audit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publica en varios publicadores; uno que falla no impide los demás. */
public final class CompositeAuditPublisher implements AuditPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(CompositeAuditPublisher.class);

  private final List<AuditPublisher> delegates;

  public CompositeAuditPublisher(List<AuditPublisher> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public void publish(AuditEvent event) {
    RuntimeException first = null;
    for (AuditPublisher p : delegates) {
      try {
        p.publish(event);
      } catch (RuntimeException e) {
        LOG.error(
            "Audit publisher {} failed for event {} type={}",
            p.getClass().getSimpleName(),
            event.id(),
            e.getClass().getName());
        if (first == null) {
          first = e;
        }
      }
    }
    if (first != null && delegates.size() == 1) {
      throw first;
    }
  }
}

package pe.axiz.spring.audit;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.context.RequestContext;

/**
 * Los envíos a la cola conservan los metadatos de solicitud y observación, sin los accesores de
 * transacción.
 */
final class AuditDeliveryContext {
  private static final ContextSnapshotFactory SNAPSHOTS =
      ContextSnapshotFactory.builder()
          .clearMissing(true)
          .captureKeyPredicate(ObservationThreadLocalAccessor.KEY::equals)
          .build();

  private AuditDeliveryContext() {}

  static final Stages.ContextProvider PROVIDER =
      () -> {
        var snapshot = SNAPSHOTS.captureAll();
        var request = RequestContext.get();
        return () -> {
          var scope = snapshot.setThreadLocals();
          var previous = RequestContext.get();
          RequestContext.restore(request);
          return () -> {
            RequestContext.restore(previous);
            scope.close();
          };
        };
      };
}

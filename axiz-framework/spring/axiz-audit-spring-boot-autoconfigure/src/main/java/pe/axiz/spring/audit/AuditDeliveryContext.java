package pe.axiz.spring.audit;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.Stages;

/** Queue submissions retain request/observation metadata, excluding transaction accessors. */
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

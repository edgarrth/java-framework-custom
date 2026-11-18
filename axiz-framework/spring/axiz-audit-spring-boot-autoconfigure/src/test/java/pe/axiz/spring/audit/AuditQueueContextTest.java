package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AsyncAuditPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditRecord;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;

class AuditQueueContextTest {
  record Delivered(Observation parent, RequestMetadata request, String transaction) {}

  @Test
  void theWorkerRetainsMetadataButExcludesEvenRegisteredTransactionAccessors() throws Exception {
    var registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    var accessor = ObservationThreadLocalAccessor.getInstance();
    var previous = accessor.getObservationRegistry();
    accessor.setObservationRegistry(registry);
    var transaction = new ThreadLocal<String>();
    ContextRegistry.getInstance()
        .registerThreadLocalAccessor(
            "test.audit.transaction", transaction::get, transaction::set, transaction::remove);
    var delivered = new CompletableFuture<Delivered>();
    var metadata = RequestMetadata.builder("correlation", "request").build();
    var parent = Observation.start("parent", registry);
    var record =
        new AuditRecord(
            "test",
            ObjectMasker.defaults(),
            IdGenerator.uuidV7(),
            Clock.systemUTC(),
            "operation",
            "account");
    try (var publisher =
        new AsyncAuditPublisher(
            event ->
                delivered.complete(
                    new Delivered(
                        registry.getCurrentObservation(), RequestContext.get(), transaction.get())),
            event -> {},
            2,
            Duration.ZERO,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            AuditDeliveryContext.PROVIDER)) {
      transaction.set("must-not-cross");
      try (var observation = parent.openScope();
          var request = RequestContext.open(metadata)) {
        publisher.publish(record.event(AuditEvent.Outcome.SUCCESS, null));
        var result = delivered.get(5, TimeUnit.SECONDS);
        assertSame(parent, result.parent());
        assertSame(metadata, result.request());
        assertNull(result.transaction());
        assertEquals("must-not-cross", transaction.get());
      }
    } finally {
      transaction.remove();
      parent.stop();
      accessor.setObservationRegistry(previous);
      ContextRegistry.getInstance().removeThreadLocalAccessor("test.audit.transaction");
    }
  }
}

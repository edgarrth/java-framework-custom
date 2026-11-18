package pe.axiz.spring.audit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.CompositeAsyncAuditPublisher;

/** Direct confirmation has no dependency on Spring transactions, SQL or an outbox. */
final class StrictDirectTargets {
  private StrictDirectTargets() {}

  static Function<AuditEvent, CompletionStage<Void>> imperative(
      List<StrictAuditSink> sinks, AuditIntegritySupport integrity) {
    if (sinks.isEmpty() || sinks.size() > 16)
      throw new IllegalStateException("Strict DIRECT audit requires 1..16 attested durable sinks");
    var targets = List.copyOf(sinks);
    var sealer = integrity.memory("strict");
    return event -> {
      var signed = sealer.seal(event);
      for (var target : targets) target.publisher().publish(signed);
      return CompletableFuture.completedFuture(null);
    };
  }

  static Function<AuditEvent, CompletionStage<Void>> nativeTargets(
      List<AsyncStrictAuditSink> sinks, AuditIntegritySupport integrity) {
    if (sinks.isEmpty() || sinks.size() > 16)
      throw new IllegalStateException(
          "Strict DIRECT audit requires 1..16 attested native durable sinks");
    List<AsyncAuditEventPublisher> targets =
        sinks.stream().map(AsyncStrictAuditSink::publisher).toList();
    var target = new CompositeAsyncAuditPublisher(targets);
    var sealer = integrity.memory("strict");
    return event -> target.publish(sealer.seal(event));
  }
}

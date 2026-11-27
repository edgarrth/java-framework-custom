package pe.axiz.spring.audit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.audit.AuditChainState;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.outbox.OutboxWriter;

/**
 * Los destinos bloqueantes los invoca exclusivamente el worker acotado de confirmación estricta.
 */
final class StrictAuditTargets {
  private StrictAuditTargets() {}

  static Function<AuditEvent, CompletionStage<Void>> durable(
      BeanFactory beans, AxizAuditProperties properties, AuditIntegritySupport integrity) {
    var writer = beans.getBean(OutboxWriter.class);
    var state = beans.getBean(AuditChainState.class);
    if (state.transactionResource() == null
        || state.transactionResource() != writer.transactionResource())
      throw new IllegalArgumentException(
          "Strict audit head and outbox must share the transaction resource");
    var sealer = integrity.sealer("strict-durable", state);
    var transaction = new TransactionTemplate(beans.getBean(PlatformTransactionManager.class));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(
        (int) ((properties.strict().timeout().toNanos() + 999_999_999L) / 1_000_000_000L));
    return event -> {
      transaction.executeWithoutResult(
          status ->
              writer.write(
                  properties.durable().destination(),
                  TransactionalAuditPublisher.envelope(sealer.seal(event)),
                  "audit"));
      return CompletableFuture.completedFuture(null);
    };
  }
}

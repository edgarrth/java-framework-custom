package pe.axiz.spring.audit;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import reactor.core.publisher.Mono;

/** Independent native confirmation; the transaction is created here, never borrowed via a stage. */
final class ReactiveStrictAuditTargets {
  private ReactiveStrictAuditTargets() {}

  static Function<AuditEvent, CompletionStage<Void>> durable(
      BeanFactory beans, AxizAuditProperties properties, AuditIntegritySupport integrity) {
    var writer = beans.getBean(ReactiveOutboxWriter.class);
    var state = beans.getBean(ReactiveAuditChainState.class);
    if (state.transactionResource() == null
        || state.transactionResource() != writer.transactionResource())
      throw new IllegalArgumentException(
          "Strict audit head and outbox must share the transaction resource");
    var definition =
        new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    definition.setTimeout(
        (int) ((properties.strict().timeout().toNanos() + 999_999_999L) / 1_000_000_000L));
    var transaction =
        TransactionalOperator.create(beans.getBean(ReactiveTransactionManager.class), definition);
    var calculator = integrity.calculator();
    String chain = integrity.chain("strict-durable");
    return event ->
        transaction
            .transactional(
                Mono.defer(
                    () ->
                        state
                            .advance(chain, head -> calculator.next(event, head, null))
                            .map(event::withIntegrity)
                            .flatMap(
                                signed ->
                                    writer.write(
                                        properties.durable().destination(),
                                        TransactionalAuditPublisher.envelope(signed),
                                        "audit"))))
            .timeout(properties.strict().timeout())
            .toFuture();
  }
}

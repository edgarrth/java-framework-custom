package pe.axiz.spring.audit;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;

/**
 * Isolates the optional reactive outbox contract; loaded only when reactive durable audit is
 * enabled.
 */
final class ReactiveDurableAudit {
  private ReactiveDurableAudit() {}

  static ReactiveAuditPublisher wrap(
      BeanFactory beans,
      AxizAuditProperties.Durable durable,
      ReactiveAuditPublisher fallback,
      AuditIntegritySupport integrity) {
    try {
      return ReactiveTransactionalAuditPublisher.builder()
          .outbox(beans.getBean(ReactiveOutboxWriter.class))
          .destination(durable.destination())
          .fallback(fallback)
          .outside(durable.outsideTransaction())
          .transactions(beans.getBean(ReactiveTransactionManager.class))
          .failureTimeout(durable.failureTimeout())
          .failureConcurrency(durable.failureConcurrency())
          .state(beans.getBean(ReactiveAuditChainState.class))
          .chain(integrity.chain("durable"))
          .calculator(integrity.calculator())
          .degradation(integrity.memory("degradation"))
          .build();
    } catch (NoSuchBeanDefinitionException | NoClassDefFoundError missing) {
      throw new IllegalStateException(
          "Reactive durable audit requires ReactiveOutboxWriter, ReactiveAuditChainState"
              + " and ReactiveTransactionManager",
          missing);
    }
  }
}

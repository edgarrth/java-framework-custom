package pe.axiz.spring.audit;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter;

/** Isolates optional R2DBC classes; loaded only when reactive durable audit is enabled. */
final class ReactiveDurableAudit {
  private ReactiveDurableAudit() {}

  static ReactiveAuditPublisher wrap(
      BeanFactory beans, AxizAuditProperties.Durable durable, ReactiveAuditPublisher fallback) {
    try {
      return new ReactiveTransactionalAuditPublisher(
          beans.getBean(ReactiveOutboxWriter.class),
          durable.destination(),
          fallback,
          durable.outsideTransaction(),
          beans.getBean(ReactiveTransactionManager.class),
          durable.failureTimeout(),
          durable.failureConcurrency());
    } catch (NoSuchBeanDefinitionException | NoClassDefFoundError missing) {
      throw new IllegalStateException(
          "Reactive durable audit requires the R2DBC outbox capability", missing);
    }
  }
}

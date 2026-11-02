package pe.axiz.spring.audit;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
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
          durable.outsideTransaction());
    } catch (NoSuchBeanDefinitionException | NoClassDefFoundError missing) {
      throw new IllegalStateException(
          "Reactive durable audit requires the R2DBC outbox capability", missing);
    }
  }
}

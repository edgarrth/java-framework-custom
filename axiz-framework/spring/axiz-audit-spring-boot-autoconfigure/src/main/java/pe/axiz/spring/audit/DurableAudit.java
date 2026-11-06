package pe.axiz.spring.audit;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.outbox.OutboxWriter;

/** Wires {@link TransactionalAuditPublisher}; loaded only when durable audit is enabled. */
final class DurableAudit {

  private DurableAudit() {}

  static AuditPublisher wrap(
      BeanFactory beans, AxizAuditProperties.Durable durable, AuditPublisher fallback) {
    OutboxWriter writer;
    try {
      writer = beans.getBean(OutboxWriter.class);
    } catch (NoSuchBeanDefinitionException e) {
      throw new IllegalStateException(
          "axiz.audit.mode=DURABLE needs the outbox capability (axiz add outbox)", e);
    }
    return new TransactionalAuditPublisher(
        writer,
        durable.destination(),
        fallback,
        durable.outsideTransaction(),
        beans.getBean(PlatformTransactionManager.class),
        durable.failureTimeout(),
        durable.failureConcurrency());
  }
}

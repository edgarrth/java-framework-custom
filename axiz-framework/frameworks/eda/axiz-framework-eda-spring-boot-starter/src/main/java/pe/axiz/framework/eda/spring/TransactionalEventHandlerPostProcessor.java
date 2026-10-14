package pe.axiz.framework.eda.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.framework.eda.ReactiveTypedEventHandler;

/**
 * Wraps every blocking {@link EventHandler} bean in a transaction when a transaction manager
 * exists; reactive handlers are wrapped by {@link ReactiveEventHandlerPostProcessor}.
 */
public class TransactionalEventHandlerPostProcessor implements BeanPostProcessor {

  private final ObjectProvider<PlatformTransactionManager> transactions;

  public TransactionalEventHandlerPostProcessor(
      ObjectProvider<PlatformTransactionManager> transactions) {
    this.transactions = transactions;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof EventHandler handler
        && !(bean instanceof TransactionalEventHandler)
        && !(bean instanceof ReactiveTypedEventHandler)) {
      PlatformTransactionManager tx = transactions.getIfAvailable();
      if (tx != null) {
        return new TransactionalEventHandler<>(handler, new TransactionTemplate(tx));
      }
    }
    return bean;
  }
}

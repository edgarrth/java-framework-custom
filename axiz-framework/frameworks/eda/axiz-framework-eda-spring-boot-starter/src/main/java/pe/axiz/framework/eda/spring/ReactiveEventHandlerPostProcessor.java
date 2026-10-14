package pe.axiz.framework.eda.spring;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import pe.axiz.framework.eda.ReactiveTypedEventHandler;

/**
 * Wraps every {@link ReactiveTypedEventHandler} bean in {@link ReactiveTransactionalEventHandler}:
 * a reactive transaction when a reactive transaction manager exists (R2DBC), the request context
 * and the handler timeout in every case.
 */
public class ReactiveEventHandlerPostProcessor implements BeanPostProcessor {

  private final ObjectProvider<ReactiveTransactionManager> transactions;
  private final boolean transactional;
  private final Duration timeout;

  public ReactiveEventHandlerPostProcessor(
      ObjectProvider<ReactiveTransactionManager> transactions,
      boolean transactional,
      Duration timeout) {
    this.transactions = transactions;
    this.transactional = transactional;
    this.timeout = timeout;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof ReactiveTypedEventHandler handler) {
      ReactiveTransactionManager tx = transactional ? transactions.getIfAvailable() : null;
      return new ReactiveTransactionalEventHandler<>(
          handler, tx == null ? null : TransactionalOperator.create(tx), timeout);
    }
    return bean;
  }
}

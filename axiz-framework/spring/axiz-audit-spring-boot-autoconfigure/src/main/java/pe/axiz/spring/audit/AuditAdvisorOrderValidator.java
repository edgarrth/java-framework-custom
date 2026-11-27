package pe.axiz.spring.audit;

import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Comprueba el orden real de los proxies; un empate inesperado nunca puede eliminar en silencio la
 * evidencia atómica.
 */
final class AuditAdvisorOrderValidator
    implements BeanPostProcessor, SmartInitializingSingleton, Ordered {
  private final ConfigurableListableBeanFactory beans;

  AuditAdvisorOrderValidator(ConfigurableListableBeanFactory beans) {
    this.beans = beans;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String name) {
    verify(bean, name);
    return bean;
  }

  @Override
  public void afterSingletonsInstantiated() {
    for (String name : beans.getSingletonNames()) {
      Object singleton = beans.getSingleton(name);
      if (singleton != null) verify(singleton, name);
    }
  }

  private static void verify(Object bean, String name) {
    if (!(bean instanceof Advised advised)) return;
    var advisors = advised.getAdvisors();
    int outer = -1;
    int transaction = -1;
    int inner = -1;
    for (int i = 0; i < advisors.length; i++) {
      Object advice = advisors[i].getAdvice();
      if (advice instanceof AuditableInterceptor || advice instanceof ReactiveAuditableInterceptor)
        outer = i;
      if (advice instanceof TransactionInterceptor) transaction = i;
      if (advice instanceof TransactionalAuditSuccessInterceptor
          || advice instanceof ReactiveTransactionalAuditSuccessInterceptor) inner = i;
    }
    if (inner < 0) return;
    if (outer < 0
        || outer >= inner
        || (transaction >= 0 && (transaction <= outer || transaction >= inner)))
      throw new IllegalStateException(
          "Invalid durable audit advisor order for "
              + name
              + ": audit(20) must wrap the transaction and durable success must execute inside it");
  }
}

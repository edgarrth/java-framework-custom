package pe.axiz.spring.messaging;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.spring.foundation.InboxCompletion;

/**
 * Wraps every blocking {@link EventHandler} bean in a transaction when a transaction manager
 * exists; reactive handlers are wrapped by {@link ReactiveEventHandlerPostProcessor}.
 */
public final class TransactionalEventHandlerPostProcessor implements BeanPostProcessor {

  private final ObjectProvider<PlatformTransactionManager> transactions;
  private final @Nullable ObjectProvider<InboxCompletion> completions;
  private final String mode;

  public TransactionalEventHandlerPostProcessor(
      ObjectProvider<PlatformTransactionManager> transactions) {
    this(transactions, null, "false");
  }

  public TransactionalEventHandlerPostProcessor(
      ObjectProvider<PlatformTransactionManager> transactions,
      @Nullable ObjectProvider<InboxCompletion> completions,
      String mode) {
    this.transactions = transactions;
    this.completions = completions;
    this.mode = mode;
    if (!Set.of("auto", "true", "false").contains(mode))
      throw new IllegalArgumentException("invalid axiz.inbox.transactional mode");
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof EventHandler handler
        && !(bean instanceof TransactionalEventHandler)
        && !(bean instanceof ReactiveTypedEventHandler)
        && !(bean instanceof ReactiveTransactionalEventHandler)) {
      PlatformTransactionManager tx = transactions.getIfAvailable();
      var completion = completions == null ? null : completions.getIfAvailable();
      boolean atomic =
          !mode.equals("false")
              && completion != null
              && tx instanceof ResourceTransactionManager resource
              && resource.getResourceFactory() == completion.transactionResource();
      if (mode.equals("true") && !atomic)
        throw new IllegalStateException(
            "axiz.inbox.transactional=true requires the same JDBC inbox and handler transaction"
                + " resource");
      if (tx != null) {
        return new TransactionalEventHandler<>(
            handler, new TransactionTemplate(tx), atomic ? completion : null);
      }
    }
    return bean;
  }
}

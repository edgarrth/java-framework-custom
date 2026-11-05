package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import pe.axiz.spring.foundation.ReactiveDispatchBoundary;
import pe.axiz.spring.foundation.ReactiveInboxCompletion;

/**
 * Wraps every {@link ReactiveTypedEventHandler} bean in {@link ReactiveTransactionalEventHandler}:
 * a reactive transaction when a reactive transaction manager exists (R2DBC), the request context
 * and the handler timeout in every case.
 */
public class ReactiveEventHandlerPostProcessor implements BeanPostProcessor {

  private final ObjectProvider<ReactiveTransactionManager> transactions;
  private final boolean transactional;
  private final Duration timeout;
  private final @Nullable ObjectProvider<ReactiveInboxCompletion> completions;
  private final String mode;
  private final @Nullable ObjectProvider<ReactiveDispatchBoundary> boundaries;

  public ReactiveEventHandlerPostProcessor(
      ObjectProvider<ReactiveTransactionManager> transactions,
      boolean transactional,
      Duration timeout) {
    this(transactions, transactional, timeout, null, "false");
  }

  public ReactiveEventHandlerPostProcessor(
      ObjectProvider<ReactiveTransactionManager> transactions,
      boolean transactional,
      Duration timeout,
      @Nullable ObjectProvider<ReactiveInboxCompletion> completions,
      String mode) {
    this(transactions, transactional, timeout, completions, mode, null);
  }

  public ReactiveEventHandlerPostProcessor(
      ObjectProvider<ReactiveTransactionManager> transactions,
      boolean transactional,
      Duration timeout,
      @Nullable ObjectProvider<ReactiveInboxCompletion> completions,
      String mode,
      @Nullable ObjectProvider<ReactiveDispatchBoundary> boundaries) {
    this.boundaries = boundaries;
    this.completions = completions;
    this.mode = mode;
    if (!Set.of("auto", "true", "false").contains(mode))
      throw new IllegalArgumentException("invalid axiz.inbox.transactional mode");
    this.transactions = transactions;
    this.transactional = transactional;
    this.timeout = timeout;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof ReactiveTypedEventHandler handler) {
      ReactiveTransactionManager tx = transactional ? transactions.getIfAvailable() : null;
      var completion = completions == null ? null : completions.getIfAvailable();
      boolean atomic =
          !mode.equals("false")
              && completion != null
              && tx instanceof R2dbcTransactionManager resource
              && resource.getConnectionFactory() == completion.transactionResource();
      if (mode.equals("true") && !atomic)
        throw new IllegalStateException(
            "axiz.inbox.transactional=true requires the same R2DBC inbox and handler transaction"
                + " resource");
      return new ReactiveTransactionalEventHandler<>(
          handler,
          tx == null ? null : TransactionalOperator.create(tx),
          timeout,
          atomic ? completion : null,
          boundaries == null ? List.of() : boundaries.orderedStream().toList());
    }
    return bean;
  }
}

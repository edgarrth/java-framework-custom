package pe.axiz.spring.jdbc;

import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.commons.tx.TransactionRunner;

/**
 * Ejecuta trabajo en una transacción de Spring nueva o existente; las excepciones checked también
 * provocan rollback.
 */
public final class SpringTransactionRunner implements TransactionRunner {

  private final TransactionTemplate template;

  public SpringTransactionRunner(PlatformTransactionManager transactions) {
    this(transactions, TransactionDefinition.PROPAGATION_REQUIRED);
  }

  /**
   * Propagación explícita para operaciones durables independientes, como las reservas de
   * secuencias.
   */
  public SpringTransactionRunner(PlatformTransactionManager transactions, int propagation) {
    this.template = new TransactionTemplate(Objects.requireNonNull(transactions));
    this.template.setPropagationBehavior(propagation);
  }

  @Override
  public <T> T inTransaction(Callable<T> work) throws Exception {
    try {
      return template.execute(
          status -> {
            try {
              return work.call();
            } catch (RuntimeException e) {
              throw e;
            } catch (Exception e) {
              throw new CheckedWrapper(e);
            }
          });
    } catch (CheckedWrapper w) {
      throw (Exception) w.getCause();
    }
  }

  private static final class CheckedWrapper extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CheckedWrapper(Exception cause) {
      super(cause);
    }
  }
}

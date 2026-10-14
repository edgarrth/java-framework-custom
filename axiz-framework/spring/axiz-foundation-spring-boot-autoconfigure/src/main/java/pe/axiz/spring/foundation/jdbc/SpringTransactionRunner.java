package pe.axiz.spring.foundation.jdbc;

import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.commons.jdbc.TransactionRunner;

/** Runs work in a new or joined Spring transaction; checked exceptions roll back too. */
public final class SpringTransactionRunner implements TransactionRunner {

  private final TransactionTemplate template;

  public SpringTransactionRunner(PlatformTransactionManager transactions) {
    this.template = new TransactionTemplate(Objects.requireNonNull(transactions));
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

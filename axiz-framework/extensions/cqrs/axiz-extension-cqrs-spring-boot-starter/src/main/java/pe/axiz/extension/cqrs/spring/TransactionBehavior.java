package pe.axiz.extension.cqrs.spring;

import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;

/** Commands run in a read-write transaction, queries in a read-only one. */
public class TransactionBehavior implements PipelineBehavior {

  private final TransactionTemplate write;
  private final TransactionTemplate read;

  public TransactionBehavior(PlatformTransactionManager transactions) {
    this.write = new TransactionTemplate(Objects.requireNonNull(transactions));
    this.read = new TransactionTemplate(transactions);
    this.read.setReadOnly(true);
  }

  @Override
  public int order() {
    return TRANSACTION;
  }

  @Override
  public <R> R handle(Invocation<R> invocation, Next<R> next) {
    return (invocation.isCommand() ? write : read).execute(status -> next.proceed());
  }
}

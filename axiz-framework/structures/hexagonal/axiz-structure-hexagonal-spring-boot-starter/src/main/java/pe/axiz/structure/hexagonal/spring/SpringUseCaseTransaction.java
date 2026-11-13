package pe.axiz.structure.hexagonal.spring;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.structure.hexagonal.UseCaseTransaction;

/** Use case transactions with Spring's transaction manager (joins an existing transaction). */
public final class SpringUseCaseTransaction implements UseCaseTransaction {

  private final TransactionTemplate readWrite;
  private final TransactionTemplate readOnly;

  public SpringUseCaseTransaction(PlatformTransactionManager transactions) {
    this.readWrite = new TransactionTemplate(Objects.requireNonNull(transactions));
    this.readOnly = new TransactionTemplate(transactions);
    this.readOnly.setReadOnly(true);
  }

  @Override
  public <T> T execute(boolean readOnlyTx, Supplier<T> work) {
    return (readOnlyTx ? readOnly : readWrite).execute(status -> work.get());
  }
}

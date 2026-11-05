package pe.axiz.spring.audit;

import java.util.function.BooleanSupplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.commons.TransactionOutcome;

/** The outer advice records failures after completion; this one keeps success inside the commit. */
final class TransactionalAuditSuccessInterceptor implements MethodInterceptor {
  private final BooleanSupplier durable;

  TransactionalAuditSuccessInterceptor(BooleanSupplier durable) {
    this.durable = durable;
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    var state = AuditableInterceptor.current();
    if (state != null && TransactionSynchronizationManager.isActualTransactionActive())
      state.transaction = JdbcTransactionOutcome.capture();
    Object result = invocation.proceed();
    if (durable.getAsBoolean()
        && state != null
        && TransactionSynchronizationManager.isActualTransactionActive()
        && state.rule.policy().applies(AuditEvent.Outcome.SUCCESS)) {
      if (state.rule.captureResult()) state.record.detail("result", result);
      state.record.outcomes(TransactionOutcome.COMMITTED, TransactionOutcome.COMMITTED).success();
      state.success = true;
    }
    return result;
  }
}

package pe.axiz.spring.audit;

import io.r2dbc.spi.R2dbcException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;
import pe.axiz.core.commons.TransactionOutcome;
import reactor.core.publisher.Mono;

/** Native transaction completion evidence, including cancellation and uncertain commit failures. */
final class ReactiveTransactionOutcome {
  private ReactiveTransactionOutcome() {}

  static Mono<JdbcTransactionOutcome> capture() {
    return Mono.defer(
        () -> {
          var outcome = new JdbcTransactionOutcome();
          return TransactionSynchronizationManager.forCurrentTransaction()
              .map(
                  manager -> {
                    if (manager.isSynchronizationActive())
                      manager.registerSynchronization(
                          new TransactionSynchronization() {
                            @Override
                            public Mono<Void> afterCompletion(int status) {
                              return Mono.fromRunnable(() -> outcome.complete(status));
                            }
                          });
                    return outcome;
                  })
              .onErrorResume(NoTransactionException.class, ignored -> Mono.just(outcome));
        });
  }

  static TransactionOutcome local(JdbcTransactionOutcome trace, @Nullable Throwable failure) {
    if (ClassUtils.isPresent("io.r2dbc.spi.R2dbcException", null) && R2dbcFailures.unknown(failure))
      return TransactionOutcome.UNKNOWN;
    return trace.local(failure);
  }

  private static final class R2dbcFailures {
    static boolean unknown(@Nullable Throwable failure) {
      var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
      for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause())
        if (cause instanceof R2dbcException sql
            && sql.getSqlState() != null
            && sql.getSqlState().startsWith("08")) return true;
      return false;
    }
  }
}

package pe.axiz.spring.audit;

import java.time.Clock;
import java.util.function.UnaryOperator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import pe.axiz.core.audit.AuditChainState;
import pe.axiz.core.audit.AuditIntegrity;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.audit.ChainCheckpoint;
import pe.axiz.core.audit.InMemoryAuditChainState;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import reactor.core.publisher.Mono;

/** Spring callback fixtures; database atomicity is tested in the provider integration modules. */
final class AuditTestTransactions {
  static final Object RESOURCE = new Object();

  private AuditTestTransactions() {}

  static AuditChainState state() {
    var state = new InMemoryAuditChainState(Clock.systemUTC(), 8);
    return new AuditChainState() {
      @Override
      public Object transactionResource() {
        return RESOURCE;
      }

      @Override
      public AuditIntegrity advance(String chain, UnaryOperator<AuditIntegrity> step) {
        return state.advance(chain, step);
      }

      @Override
      public ChainCheckpoint checkpoint(String chain) {
        return state.checkpoint(chain);
      }
    };
  }

  static AuditSealer durable() {
    return new AuditSealer("durable", AuditSealer.Mode.HASH, state(), null, null);
  }

  static AuditSealer fallback() {
    return new AuditSealer(
        "degradation",
        AuditSealer.Mode.HASH,
        new InMemoryAuditChainState(Clock.systemUTC(), 8),
        null,
        null);
  }

  static ReactiveAuditChainState reactiveState() {
    var state = state();
    return new ReactiveAuditChainState() {
      @Override
      public Object transactionResource() {
        return RESOURCE;
      }

      @Override
      public Mono<AuditIntegrity> advance(String chain, UnaryOperator<AuditIntegrity> step) {
        return Mono.fromSupplier(() -> state.advance(chain, step));
      }

      @Override
      public Mono<ChainCheckpoint> checkpoint(String chain) {
        return Mono.fromSupplier(() -> state.checkpoint(chain));
      }
    };
  }

  static final class Jdbc extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  static final class Reactive extends AbstractReactiveTransactionManager {
    @Override
    protected Object doGetTransaction(TransactionSynchronizationManager manager) {
      return new Object();
    }

    @Override
    protected Mono<Void> doBegin(
        TransactionSynchronizationManager manager,
        Object transaction,
        TransactionDefinition definition) {
      return Mono.empty();
    }

    @Override
    protected Mono<Void> doCommit(
        TransactionSynchronizationManager manager, GenericReactiveTransaction status) {
      return Mono.empty();
    }

    @Override
    protected Mono<Void> doRollback(
        TransactionSynchronizationManager manager, GenericReactiveTransaction status) {
      return Mono.empty();
    }
  }
}

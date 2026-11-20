package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.r2dbc.spi.R2dbcNonTransientResourceException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.core.commons.TransactionOutcome;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * Evidence boundaries must distinguish rollback, uncertain commit and nested failure publication.
 */
class AuditBoundaryEvidenceTest {
  @Test
  void completedTransactionsNeverTurnTransportUncertaintyIntoConfirmedRollback() {
    var trace = JdbcTransactionOutcome.capture();
    assertThat(trace.local(null)).isEqualTo(TransactionOutcome.UNKNOWN);
    assertThat(trace.local(new UnexpectedRollbackException("rolled back")))
        .isEqualTo(TransactionOutcome.ROLLED_BACK);
    trace.complete(TransactionSynchronization.STATUS_COMMITTED);
    assertThat(trace.local(null)).isEqualTo(TransactionOutcome.COMMITTED);
    assertThat(trace.local(new SQLException("business", "23505")))
        .isEqualTo(TransactionOutcome.COMMITTED);
    assertThat(trace.local(new SQLException("business"))).isEqualTo(TransactionOutcome.COMMITTED);
    for (var failure :
        new Throwable[] {
          new SQLRecoverableException(),
          new SQLException("connection", "08006"),
          new TransactionSystemException("commit uncertain")
        }) {
      assertThat(trace.local(new IllegalStateException("wrapper", failure)))
          .isEqualTo(TransactionOutcome.UNKNOWN);
    }
    trace.complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(trace.local(new IllegalStateException())).isEqualTo(TransactionOutcome.ROLLED_BACK);
    trace.complete(TransactionSynchronization.STATUS_UNKNOWN);
    assertThat(trace.local(null)).isEqualTo(TransactionOutcome.UNKNOWN);
    var cyclic = new IllegalStateException();
    cyclic.initCause(new IllegalArgumentException(cyclic));
    assertThat(trace.local(cyclic)).isEqualTo(TransactionOutcome.UNKNOWN);
  }

  @Test
  void actualCompletionCallbacksAndNativeConnectionFailuresDetermineTheOutcome() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      var trace = JdbcTransactionOutcome.capture();
      var callbacks = TransactionSynchronizationManager.getSynchronizations();
      assertThat(callbacks).hasSize(1);
      callbacks.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
      assertThat(trace.local(null)).isEqualTo(TransactionOutcome.COMMITTED);
      assertThat(ReactiveTransactionOutcome.local(trace, null))
          .isEqualTo(TransactionOutcome.COMMITTED);
      assertThat(
              ReactiveTransactionOutcome.local(
                  trace, new R2dbcNonTransientResourceException("lost", "08006")))
          .isEqualTo(TransactionOutcome.UNKNOWN);
      assertThat(
              ReactiveTransactionOutcome.local(
                  trace, new R2dbcNonTransientResourceException("constraint", "23505")))
          .isEqualTo(TransactionOutcome.COMMITTED);
      assertThat(
              ReactiveTransactionOutcome.local(
                  trace, new R2dbcNonTransientResourceException("other")))
          .isEqualTo(TransactionOutcome.COMMITTED);
      var cyclic = new IllegalStateException();
      cyclic.initCause(new IllegalArgumentException(cyclic));
      assertThat(ReactiveTransactionOutcome.local(trace, cyclic))
          .isEqualTo(TransactionOutcome.COMMITTED);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(ReactiveTransactionOutcome.capture().block(Duration.ofSeconds(3)).local(null))
        .isEqualTo(TransactionOutcome.UNKNOWN);
  }

  @Test
  void nestedSynchronousBoundariesFlushOnceAndAlwaysReleaseThreadContext() {
    var written = new AtomicInteger();
    var outer = PendingAuditFailures.open();
    var state = PendingAuditFailures.current();
    try (var inner = PendingAuditFailures.open()) {
      assertThat(PendingAuditFailures.current()).isSameAs(state);
      for (int i = 0; i < 1024; i++) assertThat(state.add(written::incrementAndGet)).isTrue();
      assertThat(state.add(written::incrementAndGet)).isFalse();
    }
    assertThat(written).hasValue(0);
    outer.close();
    outer.close();
    assertThat(written).hasValue(1024);
    assertThat(PendingAuditFailures.current()).isNull();
    var failing = PendingAuditFailures.open();
    PendingAuditFailures.current()
        .add(
            () -> {
              throw new IllegalStateException("sink unavailable");
            });
    assertThrows(IllegalStateException.class, failing::close);
    assertThat(PendingAuditFailures.current()).isNull();
  }

  @Test
  void nativeBoundariesAreSubscriptionOwnedBoundedAndDrainOnErrorAndCancellation() {
    var written = new AtomicInteger();
    assertThat(ReactivePendingAuditFailures.current(Context.empty())).isNull();
    var buffer = new ReactivePendingAuditFailures.Buffer();
    for (int i = 0; i < 1024; i++)
      assertThat(buffer.add(() -> Mono.fromRunnable(written::incrementAndGet))).isTrue();
    assertThat(buffer.add(Mono::empty)).isFalse();
    buffer.drain().block(Duration.ofSeconds(3));
    buffer.drain().block(Duration.ofSeconds(3));
    assertThat(written).hasValue(1024);
    Mono<String> queued =
        Mono.deferContextual(
            context -> {
              var current = ReactivePendingAuditFailures.current(context);
              assertThat(current).isNotNull();
              current.add(() -> Mono.fromRunnable(written::incrementAndGet));
              return Mono.just("business");
            });
    StepVerifier.create(
            ReactivePendingAuditFailures.boundary(ReactivePendingAuditFailures.boundary(queued)))
        .expectNext("business")
        .verifyComplete();
    StepVerifier.create(
            ReactivePendingAuditFailures.boundary(
                ReactivePendingAuditFailures.boundary(queued.flux())))
        .expectNext("business")
        .verifyComplete();
    StepVerifier.create(
            ReactivePendingAuditFailures.boundary(
                queued.then(Mono.error(new IllegalStateException("business failed")))))
        .expectErrorMessage("business failed")
        .verify();
    StepVerifier.create(
            ReactivePendingAuditFailures.boundary(
                queued.flux().concatWith(Flux.error(new IllegalStateException("stream failed")))))
        .expectNext("business")
        .expectErrorMessage("stream failed")
        .verify();
    StepVerifier.create(ReactivePendingAuditFailures.boundary(queued.then(Mono.never())))
        .thenAwait(Duration.ofMillis(10))
        .thenCancel()
        .verify();
    StepVerifier.create(
            ReactivePendingAuditFailures.boundary(queued.flux().concatWith(Flux.never())))
        .expectNext("business")
        .thenCancel()
        .verify();
    assertThat(written).hasValue(1030);
  }

  @Test
  void durableProxyOrderIsValidatedOnActualAdvisorsForBothStacks() {
    var beans = new DefaultListableBeanFactory();
    var validator = new AuditAdvisorOrderValidator(beans);
    assertThat(validator.postProcessAfterInitialization(new Object(), "plain")).isNotNull();
    MethodInterceptor unrelated = invocation -> invocation.proceed();
    for (boolean reactive : new boolean[] {false, true}) {
      MethodInterceptor outer =
          reactive ? mock(ReactiveAuditableInterceptor.class) : mock(AuditableInterceptor.class);
      MethodInterceptor inner =
          reactive
              ? mock(ReactiveTransactionalAuditSuccessInterceptor.class)
              : mock(TransactionalAuditSuccessInterceptor.class);
      var transaction = mock(TransactionInterceptor.class);
      for (MethodInterceptor[] order :
          new MethodInterceptor[][] {{unrelated}, {outer, inner}, {outer, transaction, inner}}) {
        var proxy = new ProxyFactory(new Object());
        for (var advice : order) proxy.addAdvice(advice);
        assertThat(validator.postProcessAfterInitialization(proxy, "valid")).isSameAs(proxy);
      }
      for (MethodInterceptor[] order :
          new MethodInterceptor[][] {
            {inner}, {inner, outer}, {transaction, outer, inner}, {outer, inner, transaction}
          }) {
        var proxy = new ProxyFactory(new Object());
        for (var advice : order) proxy.addAdvice(advice);
        assertThrows(
            IllegalStateException.class,
            () -> validator.postProcessAfterInitialization(proxy, "invalid"));
      }
    }
    beans.registerSingleton("plain", new Object());
    validator.afterSingletonsInstantiated();
    assertThat(validator.getOrder()).isEqualTo(Integer.MAX_VALUE);
  }
}

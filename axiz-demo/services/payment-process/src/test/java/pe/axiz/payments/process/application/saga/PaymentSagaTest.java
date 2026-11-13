package pe.axiz.payments.process.application.saga;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.money.Money;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;
import pe.axiz.core.saga.ContextCodec;
import pe.axiz.core.saga.InMemorySagaStateStore;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.payments.process.application.port.out.Accounts;
import pe.axiz.payments.process.application.port.out.PaymentOrders;
import pe.axiz.payments.process.application.port.out.Transfers;
import pe.axiz.payments.process.application.usecase.GetPaymentProcessUseCase;
import pe.axiz.payments.process.application.usecase.StartPaymentProcessUseCase;
import pe.axiz.payments.process.domain.PaymentInstruction;
import pe.axiz.payments.process.domain.PaymentProcess;
import pe.axiz.payments.process.domain.ProcessStatus;

class PaymentSagaTest {

  static final ErrorDefinition REJECTED =
      ErrorDefinition.of("PAYMENTS-NETWORKGATEWAY-0001", ErrorCategory.UNPROCESSABLE, "rejected");

  /** Records every downstream call; failures are injected per operation. */
  static final class Downstream implements PaymentOrders, Accounts, Transfers {
    final List<String> calls = new ArrayList<>();
    final Map<String, RuntimeException> failures = new HashMap<>();
    final Map<String, Integer> transientFailures = new HashMap<>();

    void call(String op) {
      Integer left = transientFailures.get(op);
      if (left != null && left > 0) {
        transientFailures.put(op, left - 1);
        throw ExternalServiceException.from(new ExternalErrorDetail("x", 503, null, null, ""));
      }
      calls.add(op);
      RuntimeException f = failures.get(op);
      if (f != null) {
        throw f;
      }
    }

    @Override
    public void markProcessing(String orderId, String key) {
      call("processing:" + key);
    }

    @Override
    public void complete(String orderId, String key) {
      call("complete:" + key);
    }

    @Override
    public void reject(String orderId, String reason, String key) {
      call("reject:" + key);
    }

    @Override
    public String debit(PaymentInstruction instruction, String key) {
      call("debit:" + key);
      return "MV-1";
    }

    @Override
    public void reverse(String movementId, String key) {
      call("reverse:" + movementId);
    }

    @Override
    public String send(PaymentInstruction instruction, String key) {
      call("transfer:" + key);
      return "NET-1";
    }
  }

  /** Context codec for tests: keeps contexts by reference. */
  static final class RefCodec implements ContextCodec<PaymentContext> {
    final Map<String, PaymentContext> values = new HashMap<>();

    @Override
    public String encode(PaymentContext context) {
      String k = "c" + values.size();
      values.put(k, context);
      return k;
    }

    @Override
    public @Nullable PaymentContext decode(String text) {
      return values.get(text);
    }
  }

  final Downstream downstream = new Downstream();
  final SagaStateStore store = new InMemorySagaStateStore();
  final SagaOrchestrator orchestrator =
      new SagaOrchestrator(
          store,
          RetryPolicy.transientFailures(3, Backoff.fixed(Duration.ZERO)),
          RetryPolicy.transientFailures(3, Backoff.fixed(Duration.ZERO)),
          SagaListener.none(),
          Clock.systemUTC());
  final StartPaymentProcessUseCase start =
      new StartPaymentProcessUseCase(
          orchestrator,
          store,
          PaymentSaga.definition(downstream, downstream, downstream),
          new RefCodec(),
          new EventMetrics(new SimpleMeterRegistry(), MetricsSettings.defaults()));

  static PaymentInstruction instruction(String orderId) {
    return new PaymentInstruction(
        orderId,
        "OP-1",
        "1910012345678",
        "00219100123456789053",
        Money.of("150.75", "PEN"),
        "C-1001");
  }

  PaymentProcess run(String orderId) {
    return start.execute(new StartPaymentProcessUseCase.Input(instruction(orderId)));
  }

  @Test
  void completesWithStableKeysPerStep() {
    PaymentProcess p = run("O-1");

    assertThat(p.status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(downstream.calls)
        .containsExactly(
            "processing:pp-O-1-processing",
            "debit:pp-O-1-debit",
            "transfer:pp-O-1-transfer",
            "complete:pp-O-1-complete");
  }

  @Test
  void aRejectedTransferReversesTheDebitAndRejectsTheOrder() {
    downstream.failures.put(
        "transfer:pp-O-2-transfer", new BusinessException(REJECTED, "network rejected"));

    PaymentProcess p = run("O-2");

    assertThat(p.status()).isEqualTo(ProcessStatus.REJECTED);
    assertThat(p.failedStep()).isEqualTo("transfer");
    assertThat(downstream.calls)
        .containsSubsequence("debit:pp-O-2-debit", "reverse:MV-1", "reject:pp-O-2-reject");
  }

  @Test
  void transientFailuresAreRetriedWithTheSameKey() {
    downstream.transientFailures.put("debit:pp-O-3-debit", 2);

    assertThat(run("O-3").status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(downstream.calls).containsOnlyOnce("debit:pp-O-3-debit");
  }

  @Test
  void afterThePivotTheProcessCompletesEvenIfBusinessIsDown() {
    downstream.failures.put("complete:pp-O-4-complete", new IllegalStateException("down"));

    PaymentProcess p = run("O-4");

    assertThat(p.status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(downstream.calls).doesNotContain("reverse:MV-1");
  }

  @Test
  void startingTwiceReturnsTheSameProcess() {
    run("O-5");
    int calls = downstream.calls.size();

    PaymentProcess again = run("O-5");

    assertThat(again.status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(downstream.calls).hasSize(calls);
    assertThat(
            new GetPaymentProcessUseCase(store).execute(new GetPaymentProcessUseCase.Input("O-5")))
        .isEqualTo(again);
  }

  @Test
  void aFailedUndoNeedsAttention() {
    downstream.failures.put(
        "transfer:pp-O-6-transfer", new BusinessException(REJECTED, "network rejected"));
    downstream.failures.put("reverse:MV-1", new BusinessException(REJECTED, "core says no"));

    PaymentProcess p = run("O-6");

    assertThat(p.status()).isEqualTo(ProcessStatus.NEEDS_ATTENTION);
    @Nullable String step =
        new GetPaymentProcessUseCase(store)
            .execute(new GetPaymentProcessUseCase.Input("O-6"))
            .failedStep();
    assertThat(step).isEqualTo("debit");
  }

  /**
   * Regression: the debit keeps timing out and its outcome is unknown. The process must not reject
   * the order or reverse anything: it waits VERIFYING.
   */
  @Test
  void anUncertainDebitIsVerifiedNeverAssumedMissing() {
    downstream.transientFailures.put("debit:pp-O-7-debit", 4); // 3 attempts + 1 verification

    PaymentProcess p = run("O-7");

    assertThat(p.status()).isEqualTo(ProcessStatus.VERIFYING);
    assertThat(p.failedStep()).isEqualTo("debit");
    assertThat(downstream.calls).containsExactly("processing:pp-O-7-processing");
  }

  @Test
  void aDebitConfirmedByVerificationContinuesThePayment() {
    downstream.transientFailures.put(
        "debit:pp-O-8-debit", 3); // every attempt fails, the check works

    PaymentProcess p = run("O-8");

    assertThat(p.status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(downstream.calls).containsOnlyOnce("debit:pp-O-8-debit");
    assertThat(downstream.calls).doesNotContain("reverse:MV-1", "reject:pp-O-8-reject");
  }

  @Test
  void aTransferWithALostAnswerNeverReversesTheDebit() {
    downstream.transientFailures.put("transfer:pp-O-9-transfer", 4);

    PaymentProcess p = run("O-9");

    assertThat(p.status()).isEqualTo(ProcessStatus.VERIFYING);
    assertThat(downstream.calls).doesNotContain("reverse:MV-1");
  }
}

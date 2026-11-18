package pe.axiz.payments.process.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.payments.process.domain.PaymentProcess;
import pe.axiz.payments.process.domain.ProcessStatus;

/** What a process looks like from the outside: its status and, when it failed, which step. */
class ProcessViewsTest {

  static SagaState state(SagaStatus status, String error) {
    return SagaState.builder().sagaId("O-1").sagaName("payment").status(status).stepIndex(2).context("ctx").error(error).updatedAt(Instant.EPOCH).version(3).build();
  }

  @Test
  void everySagaStatusHasAProcessStatus() {
    assertThat(ProcessViews.status(SagaStatus.RUNNING)).isEqualTo(ProcessStatus.RUNNING);
    assertThat(ProcessViews.status(SagaStatus.COMPENSATING)).isEqualTo(ProcessStatus.COMPENSATING);
    assertThat(ProcessViews.status(SagaStatus.IN_DOUBT)).isEqualTo(ProcessStatus.VERIFYING);
    assertThat(ProcessViews.status(SagaStatus.COMPLETED)).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(ProcessViews.status(SagaStatus.COMPENSATED)).isEqualTo(ProcessStatus.REJECTED);
    assertThat(ProcessViews.status(SagaStatus.FAILED)).isEqualTo(ProcessStatus.NEEDS_ATTENTION);
  }

  @Test
  void theFailedStepComesFromTheStoredErrorAndNoContextLeaves() {
    PaymentProcess failed =
        ProcessViews.of(state(SagaStatus.COMPENSATED, "transfer: network rejected"));
    assertThat(failed).isEqualTo(new PaymentProcess("O-1", ProcessStatus.REJECTED, "transfer"));

    assertThat(ProcessViews.failedStep("compensation debit: core down")).isEqualTo("debit");
    assertThat(ProcessViews.failedStep("no step here")).isNull();
    assertThat(ProcessViews.failedStep(null)).isNull();
    assertThat(ProcessViews.of(state(SagaStatus.RUNNING, null)).failedStep()).isNull();
  }
}

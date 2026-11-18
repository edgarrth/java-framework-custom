package pe.axiz.payments.process.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import pe.axiz.core.money.Money;
import pe.axiz.core.saga.SagaRecovery;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.payments.process.application.saga.PaymentContext;
import pe.axiz.payments.process.application.usecase.GetPaymentProcessUseCase;
import pe.axiz.payments.process.application.usecase.Processes;
import pe.axiz.payments.process.domain.PaymentInstruction;
import pe.axiz.payments.process.domain.ProcessStatus;

/** A process interrupted mid-way (crash, redeploy) is resumed by the recovery job. */
class SagaRecoveryJobTest {

  final Processes processes = new Processes();

  @Test
  void resumesIdleProcessesAndDoesNothingWithoutThem() {
    Clock later = Clock.fixed(Instant.now().plusSeconds(600), ZoneOffset.UTC);
    SagaRecoveryJob job =
        new SagaRecoveryJob(
            new SagaRecovery(processes.store, processes.orchestrator, later)
                .register(processes.saga, processes.codec));

    job.resumeStale();
    assertThat(processes.calls).isEmpty();

    PaymentContext context =
        PaymentContext.start(
            new PaymentInstruction(
                "O-91",
                "OP-9",
                "1910012345678",
                "00219100123456789053",
                Money.of("10.00", "PEN"),
                "C-1001"));
    processes.store.create(
        SagaState.builder()
            .sagaId("O-91")
            .sagaName(processes.saga.name())
            .status(SagaStatus.RUNNING)
            .stepIndex(0)
            .context(processes.codec.encode(context))
            .error(null)
            .updatedAt(Instant.now())
            .version(0)
            .build());

    job.resumeStale();

    assertThat(processes.calls).contains("processing O-91", "complete O-91");
    assertThat(processes.get.execute(new GetPaymentProcessUseCase.Input("O-91")).status())
        .isEqualTo(ProcessStatus.COMPLETED);
  }
}

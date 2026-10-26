package pe.axiz.payments.process.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.payments.process.api.model.ProcessRequest;
import pe.axiz.payments.process.api.model.ProcessView;
import pe.axiz.payments.process.application.usecase.Processes;

/** The operations endpoint over the real saga: a process started by hand and read back. */
class PaymentProcessesControllerTest {

  final Processes processes = new Processes();
  final PaymentProcessesController controller =
      new PaymentProcessesController(processes.start, processes.get);

  @Test
  void startingAProcessRunsTheSagaAndAnswers201() {
    var response =
        controller.startPaymentProcess(
            new ProcessRequest()
                .paymentOrderId("O-1")
                .operationNumber("OP-1")
                .debtorAccount("1910012345678")
                .creditorCci("00219100123456789053")
                .amount(new BigDecimal("150.75"))
                .currency("PEN")
                .customerId("C-1001"));

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    ProcessView view = response.getBody();
    assertThat(view).isNotNull();
    assertThat(view.getProcessId()).isEqualTo("O-1");
    assertThat(view.getStatus()).isEqualTo("COMPLETED");
    assertThat(view.getFailedStep()).isNull();
    assertThat(processes.calls)
        .containsExactly(
            "processing O-1",
            "debit 1910012345678 PEN 150.75",
            "transfer 00219100123456789053",
            "complete O-1");

    var read = controller.getPaymentProcess("O-1");
    assertThat(read.getStatusCode().value()).isEqualTo(200);
    assertThat(read.getBody()).isNotNull();
    assertThat(read.getBody().getStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void anUnknownProcessIsNotFound() {
    assertThatThrownBy(() -> controller.getPaymentProcess("O-404"))
        .isInstanceOf(NotFoundException.class);
  }
}

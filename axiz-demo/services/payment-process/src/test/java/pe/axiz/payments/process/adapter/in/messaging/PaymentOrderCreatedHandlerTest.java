package pe.axiz.payments.process.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import pe.axiz.payments.process.application.usecase.GetPaymentProcessUseCase;
import pe.axiz.payments.process.application.usecase.Processes;
import pe.axiz.payments.process.domain.ProcessStatus;

/** The process starts once per order, however many times the event is delivered. */
class PaymentOrderCreatedHandlerTest {

  final Processes processes = new Processes();
  final PaymentOrderCreatedHandler handler = new PaymentOrderCreatedHandler(processes.start);

  static PaymentOrderCreatedHandler.PaymentOrderCreated event() {
    return new PaymentOrderCreatedHandler.PaymentOrderCreated(
        "O-7",
        "C-1001",
        "OP-7",
        "1910012345678",
        "00219100123456789053",
        new BigDecimal("150.75"),
        "PEN");
  }

  @Test
  void handlesTheCreatedEventOfPaymentOrders() {
    assertThat(handler.eventType()).isEqualTo("pe.axiz.payments.payment-order.created.v1");
    assertThat(handler.payloadType())
        .isEqualTo(PaymentOrderCreatedHandler.PaymentOrderCreated.class);
  }

  @Test
  void inexactEventDoesNotStartThePaymentProcess() {
    var e = event();
    var invalid =
        new PaymentOrderCreatedHandler.PaymentOrderCreated(
            e.orderId(),
            e.customerId(),
            e.operationNumber(),
            e.debtorAccount(),
            e.creditorCci(),
            new BigDecimal("150.005"),
            e.currency());
    assertThatThrownBy(() -> handler.handle(invalid, null))
        .isInstanceOf(pe.axiz.core.errors.BusinessException.class);
    assertThat(processes.calls).isEmpty();
  }

  @Test
  void aRedeliveredEventDoesNotStartASecondProcess() throws Exception {
    handler.handle(event(), null);
    int calls = processes.calls.size();
    handler.handle(event(), null);

    assertThat(processes.calls).hasSize(calls);
    assertThat(processes.calls).contains("debit 1910012345678 PEN 150.75");
    assertThat(processes.get.execute(new GetPaymentProcessUseCase.Input("O-7")).status())
        .isEqualTo(ProcessStatus.COMPLETED);
  }
}

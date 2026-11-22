package pe.axiz.payments.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.core.money.Money;
import pe.axiz.payments.process.application.port.out.Accounts;
import pe.axiz.payments.process.application.port.out.PaymentOrders;
import pe.axiz.payments.process.application.port.out.Transfers;
import pe.axiz.payments.process.application.usecase.GetPaymentProcessUseCase;
import pe.axiz.payments.process.application.usecase.StartPaymentProcessUseCase;
import pe.axiz.payments.process.domain.PaymentInstruction;
import pe.axiz.payments.process.domain.ProcessStatus;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations and health, and runs the payment saga through its use cases on the JDBC
 * saga store. Needs Docker.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.messaging.kafka.authentication=configured",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentProcessApplicationTest {

  @MockitoBean PaymentOrders orders;
  @MockitoBean Accounts accounts;
  @MockitoBean Transfers transfers;

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  /**
   * Use cases run in a transaction: the JDBC saga store must still commit its state and execution
   * lease on its own connection, and the process must be readable afterwards.
   */
  @Test
  void theSagaCompletesThroughTheTransactionalUseCasesOnTheJdbcStore(
      @Autowired StartPaymentProcessUseCase start,
      @Autowired GetPaymentProcessUseCase read,
      TestReporter evidence) {
    when(accounts.debit(any(), anyString())).thenReturn("MOV-1");
    when(transfers.send(any(), anyString())).thenReturn("TRF-1");
    var instruction =
        new PaymentInstruction(
            "0190a1b2-0000-7000-8000-00000000c0de",
            "OP20261121-00000001",
            "1910012345678",
            "00219100123456789053",
            Money.of(new BigDecimal("150.75"), "PEN"),
            "C-1001");

    var started = start.execute(new StartPaymentProcessUseCase.Input(instruction));

    assertEquals(ProcessStatus.COMPLETED, started.status());
    assertEquals(
        ProcessStatus.COMPLETED,
        read.execute(new GetPaymentProcessUseCase.Input(instruction.orderId())).status());
    // a redelivery of the same order returns the stored process instead of paying twice
    assertEquals(
        ProcessStatus.COMPLETED,
        start.execute(new StartPaymentProcessUseCase.Input(instruction)).status());
    evidence.publishEntry("axiz.capability.saga", "aurora,servlet");
  }
}

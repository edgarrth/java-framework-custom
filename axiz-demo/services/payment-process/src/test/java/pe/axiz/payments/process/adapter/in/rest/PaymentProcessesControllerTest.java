package pe.axiz.payments.process.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.payments.process.adapter.in.rest.model.ProcessRequest;
import pe.axiz.payments.process.adapter.in.rest.model.ProcessView;
import pe.axiz.payments.process.application.usecase.Processes;
import pe.axiz.spring.test.OpenApiResponses;

/** The operations endpoint over the real saga: a process started by hand and read back. */
class PaymentProcessesControllerTest {

  final Processes processes = new Processes();
  final PaymentProcessesController controller =
      new PaymentProcessesController(processes.start, processes.get);

  @Test
  void responseExamplesAndSerializedSagaMatchOpenApi() throws Exception {
    var contract = new OpenApiResponses(Path.of("src/main/openapi/openapi.yaml"));
    assertThat(contract.assertExamples()).isPositive();
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    var created =
        mvc.perform(
                post("/v1/payment-processes")
                    .contentType("application/json")
                    .content(
                        """
        {"paymentOrderId":"O-1","operationNumber":"OP-1","debtorAccount":"1910012345678",
         "creditorCci":"00219100123456789053","amount":150.75,"currency":"PEN","customerId":"C-1001"}
        """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    contract.assertResponse(
        "/v1/payment-processes",
        "post",
        created.getStatus(),
        created.getContentType(),
        created.getContentAsString());
    var read =
        mvc.perform(get("/v1/payment-processes/O-1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    contract.assertResponse(
        "/v1/payment-processes/{processId}",
        "get",
        read.getStatus(),
        read.getContentType(),
        read.getContentAsString());
  }

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

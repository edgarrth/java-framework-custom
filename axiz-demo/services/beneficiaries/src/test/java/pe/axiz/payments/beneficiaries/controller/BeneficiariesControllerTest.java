package pe.axiz.payments.beneficiaries.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.payments.beneficiaries.model.api.Beneficiary;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryPage;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryRequest;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryUpdate;
import pe.axiz.payments.beneficiaries.service.InMemoryBeneficiaries;
import pe.axiz.payments.beneficiaries.service.Services;
import pe.axiz.spring.test.OpenApiResponses;
import pe.axiz.spring.web.PageRequestArgumentResolver;
import pe.axiz.spring.web.PageRequestParser;

/**
 * El adaptador REST sobre el servicio real: lo que responde cada operación (estado y cuerpo en los
 * modelos del contrato) y cómo los modelos del contrato se convierten en los del servicio.
 */
class BeneficiariesControllerTest {

  static final String CCI = "00219100123456789053";
  static final String OTHER_CCI = "00319300987654321003";

  @Test
  void responseExamplesAndSerializedPagesMatchOpenApi() throws Exception {
    var contract = new OpenApiResponses(Path.of("src/main/openapi/openapi.yaml"));
    assertThat(contract.assertExamples()).isPositive();
    controller.createBeneficiary("C-1", request("Landlord", CCI));
    controller.createBeneficiary("C-1", request("Gym", OTHER_CCI));
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(
                new PageRequestArgumentResolver(new PageRequestParser(20, 100)))
            .build();
    for (String size : new String[] {"1", "20"}) {
      var response =
          mvc.perform(get("/v1/customers/C-1/beneficiaries").param("size", size))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      contract.assertResponse(
          "/v1/customers/{customerId}/beneficiaries",
          "get",
          response.getStatus(),
          response.getContentType(),
          response.getContentAsString());
    }
  }

  final InMemoryBeneficiaries repository = new InMemoryBeneficiaries();
  final BeneficiariesController controller =
      new BeneficiariesController(
          Services.beneficiaries(
              repository,
              Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC),
              new SimpleMeterRegistry(),
              5));

  static BeneficiaryRequest request(String alias, String cci) {
    return new BeneficiaryRequest()
        .alias(alias)
        .holderName(" Ana Torres ")
        .cci(cci)
        .currency("PEN");
  }

  @Test
  void createAnswers201WithTheContractModel() {
    var response = controller.createBeneficiary("C-1", request(" Landlord ", CCI));

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    Beneficiary body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getId()).isEqualTo("B-1");
    assertThat(body.getCustomerId()).isEqualTo("C-1");
    assertThat(body.getAlias()).isEqualTo("Landlord");
    assertThat(body.getHolderName()).isEqualTo("Ana Torres");
    assertThat(body.getCci()).isEqualTo(CCI);
    assertThat(body.getCurrency()).isEqualTo("PEN");
    assertThat(repository.allActive("C-1")).hasSize(1);
  }

  @Test
  void getAndUpdateAnswer200() {
    controller.createBeneficiary("C-1", request("Landlord", CCI));

    var read = controller.getBeneficiary("C-1", "B-1");
    var renamed = controller.updateBeneficiary("C-1", "B-1", new BeneficiaryUpdate().alias("Rent"));

    assertThat(read.getStatusCode().value()).isEqualTo(200);
    assertThat(read.getBody()).isNotNull();
    assertThat(read.getBody().getAlias()).isEqualTo("Landlord");
    assertThat(renamed.getStatusCode().value()).isEqualTo(200);
    assertThat(renamed.getBody()).isNotNull();
    assertThat(renamed.getBody().getAlias()).isEqualTo("Rent");
    assertThat(controller.getBeneficiary("C-1", "B-1").getBody().getAlias()).isEqualTo("Rent");
  }

  @Test
  void listPagesWithTheCursorOfThePreviousPage() {
    controller.createBeneficiary("C-1", request("Landlord", CCI));
    controller.createBeneficiary("C-1", request("Gym", OTHER_CCI));

    BeneficiaryPage first =
        controller.listBeneficiaries("C-1", null, PageRequest.of(0, 1)).getBody();
    assertThat(first).isNotNull();
    assertThat(first.getItems()).extracting(Beneficiary::getAlias).containsExactly("Landlord");
    assertThat(first.getNextCursor()).isNotNull();

    BeneficiaryPage second =
        controller.listBeneficiaries("C-1", first.getNextCursor(), PageRequest.of(0, 1)).getBody();
    assertThat(second).isNotNull();
    assertThat(second.getItems()).extracting(Beneficiary::getAlias).containsExactly("Gym");
    assertThat(second.getNextCursor()).isNull();
  }

  @Test
  void deleteAnswers204AndTheBeneficiaryIsGone() {
    controller.createBeneficiary("C-1", request("Landlord", CCI));

    var response = controller.deleteBeneficiary("C-1", "B-1");

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    assertThat(repository.rows).hasSize(1);
    assertThatThrownBy(() -> controller.getBeneficiary("C-1", "B-1"))
        .isInstanceOf(NotFoundException.class);
    assertThat(controller.listBeneficiaries("C-1", null, PageRequest.first()).getBody())
        .satisfies(page -> assertThat(page.getItems()).isEmpty());
  }
}

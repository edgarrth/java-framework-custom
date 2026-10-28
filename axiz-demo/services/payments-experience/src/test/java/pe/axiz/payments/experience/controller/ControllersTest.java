package pe.axiz.payments.experience.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.featureflags.FeatureFlags;
import pe.axiz.core.featureflags.FlagDefinition;
import pe.axiz.core.featureflags.MapFlagProvider;
import pe.axiz.payments.experience.api.model.BeneficiaryList;
import pe.axiz.payments.experience.api.model.BeneficiaryView;
import pe.axiz.payments.experience.api.model.PaymentRequest;
import pe.axiz.payments.experience.api.model.PaymentView;
import pe.axiz.payments.experience.composition.BeneficiariesComposition;
import pe.axiz.payments.experience.composition.BeneficiaryDirectory;
import pe.axiz.payments.experience.composition.Fakes;
import pe.axiz.payments.experience.composition.PaymentsComposition;
import pe.axiz.spring.test.OpenApiResponses;
import pe.axiz.spring.web.PageRequestParser;
import pe.axiz.spring.web.ReactivePageRequestArgumentResolver;
import reactor.core.publisher.Mono;

/**
 * The channel REST adapters over the real compositions and in-memory downstream services: status
 * codes and bodies of each operation, for the customer of the token.
 */
class ControllersTest {

  final Fakes.Crud crud =
      new Fakes.Crud()
          .with("C-1", "B-01", "Landlord", Fakes.CCI, "PEN")
          .with("C-2", "B-09", "Other customer", Fakes.CCI, "PEN");
  final BeneficiaryDirectory directory = new BeneficiaryDirectory(crud);
  final PaymentsController payments =
      new PaymentsController(
          new PaymentsComposition(
              new Fakes.Business(),
              directory,
              new FeatureFlags(new MapFlagProvider(List.of(FlagDefinition.off("payments-usd")))),
              Duration.ofSeconds(1)));
  final BeneficiariesController beneficiaries =
      new BeneficiariesController(new BeneficiariesComposition(directory));

  @Test
  void responseExamplesAndSerializedBeneficiariesMatchOpenApi() throws Exception {
    var contract = new OpenApiResponses(Path.of("src/main/openapi/openapi.yaml"));
    assertThat(contract.assertExamples()).isPositive();
    var client =
        WebTestClient.bindToController(beneficiaries)
            .argumentResolvers(
                config ->
                    config.addCustomResolver(
                        new ReactivePageRequestArgumentResolver(new PageRequestParser(20, 100))))
            .webFilter(
                (exchange, chain) -> chain.filter(exchange).contextWrite(Fakes.signedIn("C-1")))
            .build();
    var response =
        client
            .get()
            .uri("/v1/beneficiaries")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult();
    contract.assertResponse(
        "/v1/beneficiaries",
        "get",
        response.getStatus().value(),
        response.getResponseHeaders().getContentType().toString(),
        response.getResponseBody());
  }

  static <T> T as(String customer, Mono<T> mono) {
    return mono.contextWrite(Fakes.signedIn(customer)).block(Duration.ofSeconds(5));
  }

  @Test
  void aPaymentIsCreatedWith201AndReadWith200() {
    var created =
        as(
            "C-1",
            payments.createPayment(
                "key-1",
                new PaymentRequest()
                    .beneficiaryId("B-01")
                    .debtorAccount("1910012345678")
                    .amount(new BigDecimal("150.75"))
                    .currency("PEN")
                    .description("Rent")));

    assertThat(created).isNotNull();
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    PaymentView view = created.getBody();
    assertThat(view).isNotNull();
    assertThat(view.getBeneficiaryAlias()).isEqualTo("Landlord");

    var read = as("C-1", payments.getPayment(view.getPaymentId()));
    assertThat(read).isNotNull();
    assertThat(read.getStatusCode().value()).isEqualTo(200);
    assertThat(read.getBody()).isNotNull();
    assertThat(read.getBody().getPaymentId()).isEqualTo(view.getPaymentId());
  }

  @Test
  void theBeneficiaryListIsTheOneOfTheSignedInCustomer() {
    var response = as("C-1", beneficiaries.listMyBeneficiaries(null, PageRequest.of(0, 20)));

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    BeneficiaryList list = response.getBody();
    assertThat(list).isNotNull();
    assertThat(list.getItems()).extracting(BeneficiaryView::getId).containsExactly("B-01");
    assertThat(list.getItems().get(0).getCreditorCci()).doesNotContain(Fakes.CCI);
  }
}

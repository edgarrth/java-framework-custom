package pe.axiz.payments.ux.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.featureflags.FeatureFlags;
import pe.axiz.core.featureflags.FlagDefinition;
import pe.axiz.core.featureflags.MapFlagProvider;
import pe.axiz.payments.ux.composition.BeneficiariesComposition;
import pe.axiz.payments.ux.composition.BeneficiaryDirectory;
import pe.axiz.payments.ux.composition.Fakes;
import pe.axiz.payments.ux.composition.PaymentsComposition;
import pe.axiz.payments.ux.composition.model.BeneficiaryList;
import pe.axiz.payments.ux.composition.model.BeneficiaryView;
import pe.axiz.payments.ux.composition.model.PaymentRequest;
import pe.axiz.payments.ux.composition.model.PaymentView;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.spring.test.OpenApiResponses;
import pe.axiz.spring.web.PageRequestParser;
import pe.axiz.spring.web.ReactivePageRequestArgumentResolver;
import reactor.core.publisher.Mono;

/**
 * The channel REST adapters over the real compositions and in-memory downstream services: status
 * codes and bodies of each operation, for the customer of the token.
 */
@ExtendWith(BlockHoundExtension.class)
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
    var client =
        WebTestClient.bindToController(payments)
            .webFilter(
                (exchange, chain) -> chain.filter(exchange).contextWrite(Fakes.signedIn("C-1")))
            .build();
    PaymentView created =
        client
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", "key-1")
            .bodyValue(
                new PaymentRequest()
                    .beneficiaryId("B-01")
                    .debtorAccount("1910012345678")
                    .amount(new BigDecimal("150.75"))
                    .currency("PEN")
                    .description("Rent"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(created).isNotNull();
    assertThat(created.getBeneficiaryAlias()).isEqualTo("Landlord");
    client
        .get()
        .uri("/v1/payments/{id}", created.getPaymentId())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.paymentId")
        .isEqualTo(created.getPaymentId())
        .jsonPath("$.beneficiaryAlias")
        .isEqualTo("Landlord");
  }

  @Test
  void theBeneficiaryListIsTheOneOfTheSignedInCustomer() {
    var response = as("C-1", beneficiaries.listMyBeneficiaries(null, PageRequest.of(0, 20)));

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    BeneficiaryList list = response.getBody();
    assertThat(list).isNotNull();
    assertThat(list.getItems()).extracting(BeneficiaryView::getId).containsExactly("B-01");
    assertThat(list.getItems().getFirst().getCreditorCci()).doesNotContain(Fakes.CCI);
  }
}

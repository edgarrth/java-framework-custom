package pe.axiz.payments.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.spring.security.AxizJwtConverter;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations, a real command/query transaction and encrypted persistence. Needs
 * Docker.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.messaging.kafka.authentication=configured",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@AutoConfigureMockMvc
@Import({
  PostgresTestConfiguration.class,
  KafkaTestConfiguration.class,
  LocalStackTestConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOrdersApplicationTest {

  /** Field encryption and blind index keys: random for every run, never committed. */
  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    EphemeralSecrets.register(registry, "field-key-v1", "blind-index-key");
  }

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void createsAnEncryptedOrderAndReplaysWithoutDuplicatingItsOutboxEvent(
      @Autowired MockMvc mvc,
      @Autowired JdbcTemplate jdbc,
      @Autowired AxizJwtConverter converter,
      TestReporter evidence)
      throws Exception {
    String customer = "C-RECIPE";
    String cci = "00219100123456789053";
    String debtor = "1910012345678";
    // MockMvc supplies a validated token fixture; the service's real mapper creates its identity.
    var identity =
        authentication(
            converter.convert(
                Jwt.withTokenValue("synthetic-test-token")
                    .header("alg", "RS256")
                    .issuer("https://issuer.example.test")
                    .subject(customer)
                    .claim("azp", "payments-web")
                    .claim("scope", "payments:channel")
                    .build()));
    String body =
        """
        {"customerId":"C-RECIPE","debtorAccount":"1910012345678",
         "creditorCci":"00219100123456789053","amount":15.75,"currency":"PEN"}
        """;
    var first =
        mvc.perform(
                post("/v1/payment-orders")
                    .with(identity)
                    .header("Idempotency-Key", "recipe-order-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(
                result -> {
                  if (result.getResolvedException() != null)
                    throw new AssertionError(
                        "Payment operation failed", result.getResolvedException());
                })
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    var replay =
        mvc.perform(
                post("/v1/payment-orders")
                    .with(identity)
                    .header("Idempotency-Key", "recipe-order-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(
                result -> {
                  if (result.getResolvedException() != null)
                    throw new AssertionError(
                        "Payment operation failed", result.getResolvedException());
                })
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(first.getHeader("Location")).startsWith("/v1/payment-orders/");
    mvc.perform(get(first.getHeader("Location")).with(identity)).andExpect(status().isOk());
    mvc.perform(
            get(first.getHeader("Location"))
                .with(
                    authentication(
                        converter.convert(
                            Jwt.withTokenValue("synthetic-other-token")
                                .header("alg", "RS256")
                                .issuer("https://issuer.example.test")
                                .subject("C-OTHER")
                                .claim("azp", "payments-web")
                                .claim("scope", "payments:channel")
                                .build()))))
        .andExpect(status().isNotFound());
    var rows =
        jdbc.queryForList(
            "SELECT id, debtor_account, creditor_cci, creditor_cci_index, created_by "
                + "FROM payment_order WHERE customer_id = ?",
            customer);
    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(row.get("debtor_account").toString()).startsWith("axz1:").doesNotContain(debtor);
    assertThat(row.get("creditor_cci").toString()).startsWith("axz1:").doesNotContain(cci);
    assertThat(row.get("creditor_cci_index").toString()).isNotBlank().doesNotContain(cci);
    assertThat(row.get("created_by")).isEqualTo(customer);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM axiz_outbox WHERE aggregate_id = ? "
                    + "AND event_type LIKE '%payment-order.created.v1'",
                Long.class, row.get("id").toString()))
        .isEqualTo(1);

    mvc.perform(get(first.getHeader("Location")))
        .andExpect(status().isUnauthorized())
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentType())
                    .startsWith("application/problem+json"));
    evidence.publishEntry("axiz.capability.foundation", "servlet");
    evidence.publishEntry("axiz.capability.jpa", "aurora,servlet");
    evidence.publishEntry("axiz.capability.outbox", "aurora,servlet");
    evidence.publishEntry("axiz.capability.crypto", "servlet");
    evidence.publishEntry("axiz.capability.crypto-kms", "servlet");
    evidence.publishEntry("axiz.capability.web", "servlet");
    evidence.publishEntry("axiz.capability.errors", "servlet");
    evidence.publishEntry("axiz.capability.security", "servlet");
  }
}

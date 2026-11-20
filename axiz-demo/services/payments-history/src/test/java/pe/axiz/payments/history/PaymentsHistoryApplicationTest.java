package pe.axiz.payments.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.time.BusinessCalendar;
import pe.axiz.extension.cqrs.spring.ReactiveQueryBus;
import pe.axiz.payments.history.application.query.GetPaymentQuery;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers) and follows a
 * payment end to end: events on Kafka, R2DBC inbox, reactive command pipeline, projection with
 * encrypted and money columns on PostgreSQL, reactive query. Needs Docker.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "axiz.config.environment=test",
      "axiz.messaging.kafka.authentication=configured",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@AutoConfigureWebTestClient
@Import({
  PostgresTestConfiguration.class,
  KafkaTestConfiguration.class,
  LocalStackTestConfiguration.class
})
@ExtendWith(BlockHoundExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentsHistoryApplicationTest {
  @AfterAll
  static void noBlockingCallWasHiddenByRetryOrErrorHandling() {
    assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
  }

  /** Field encryption and blind index keys: random for every run, never committed. */
  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    EphemeralSecrets.register(registry, "field-key-v1", "blind-index-key");
  }

  private static final Duration T = Duration.ofSeconds(30);

  @Autowired EventFactory events;
  // Test fixture acting as an external producer on the JUnit thread. The application is
  // consumer-only; declaring a reactive Kafka publishing destination must still fail startup.
  @Autowired EventPublisher fixturePublisher;
  @Autowired ReactiveQueryBus queries;
  @Autowired DatabaseClient db;
  @Autowired BusinessCalendar calendar;
  @LocalServerPort int port;

  @Test
  void startsAndReportsHealth(@Autowired WebTestClient client) {
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }

  @Test
  void projectsThePaymentOrderEvents(TestReporter evidence, @Autowired WebTestClient client) {
    String orderId = UUID.randomUUID().toString();
    fixturePublisher.publish(
        "kafka:payments",
        events.create(
            orderId + "-created",
            "payment-order.created.v1",
            orderId,
            Map.of(
                "orderId", orderId,
                "operationNumber", "OP20261011-00000001",
                "customerId", "C-1001",
                "debtorAccount", "1910012345678",
                "creditorCci", "00219100123456789053",
                "amount", new BigDecimal("150.75"),
                "currency", "PEN",
                "processingDate", LocalDate.parse("2026-10-12").toString()),
            null));
    fixturePublisher.publish(
        "kafka:payments",
        events.create(
            orderId + "-completed",
            "payment-order.status-changed.v1",
            orderId,
            Map.of(
                "orderId", orderId,
                "customerId", "C-1001",
                "previous", "PROCESSING",
                "status", "COMPLETED"),
            null));

    // the query runs through the access policy, as the API does: only the owner reads the payment
    // (another customer gets "not found"). Until the first event is projected it is not found
    // either, so that is the only error retried
    PaymentHistory projected =
        Mono.defer(() -> queries.ask(new GetPaymentQuery(orderId)))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(customer("C-1001")))
            .filter(h -> h.status() == PaymentStatus.COMPLETED)
            .repeatWhenEmpty(r -> r.delayElements(Duration.ofMillis(200)).take(100))
            .retryWhen(
                Retry.fixedDelay(100, Duration.ofMillis(200))
                    .filter(NotFoundException.class::isInstance))
            .block(T);

    assertThat(projected).isNotNull();
    assertThat(projected.amount()).hasToString("PEN 150.75");
    assertThat(projected.maskedCreditorCci()).isEqualTo("****************9053");
    String stored =
        db.sql("SELECT creditor_cci, created_by FROM payment_history WHERE payment_id = :id")
            .bind("id", orderId)
            .map(
                row ->
                    row.get("creditor_cci", String.class)
                        + "|"
                        + row.get("created_by", String.class))
            .one()
            .block(T);
    // encrypted at rest, audited by the R2DBC starter
    assertThat(stored).doesNotContain("00219100123456789053").doesNotEndWith("|null");

    assertThatThrownBy(
            () ->
                queries
                    .ask(new GetPaymentQuery(orderId))
                    .contextWrite(
                        ReactiveSecurityContextHolder.withAuthentication(customer("C-1002")))
                    .block(T))
        .isInstanceOf(NotFoundException.class);

    WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .get()
        .uri("/v1/payments/" + orderId)
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentTypeCompatibleWith("application/problem+json")
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(401);
    assertThat(calendar.addBusinessDays(LocalDate.of(2026, 11, 20), 1))
        .isEqualTo(LocalDate.of(2026, 11, 23));
    evidence.publishEntry("axiz.capability.foundation", "reactive");
    evidence.publishEntry("axiz.capability.web", "reactive");
    evidence.publishEntry("axiz.capability.errors", "reactive");
    evidence.publishEntry("axiz.capability.security", "reactive");
    evidence.publishEntry("axiz.capability.r2dbc", "r2dbc,reactive");
    evidence.publishEntry("axiz.capability.inbox", "r2dbc");
    evidence.publishEntry("axiz.capability.messaging-kafka", "reactive");
  }

  private static Authentication customer(String id) {
    return UsernamePasswordAuthenticationToken.authenticated(id, "n/a", List.of());
  }
}

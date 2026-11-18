package pe.axiz.payments.history.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.core.money.Money;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.extension.cqrs.spring.ReactiveQueryBus;
import pe.axiz.payments.history.adapter.in.rest.model.PaymentHistoryItem;
import pe.axiz.payments.history.adapter.in.rest.model.PaymentHistoryPage;
import pe.axiz.payments.history.adapter.in.rest.model.PaymentStatus;
import pe.axiz.payments.history.application.InMemoryPaymentHistories;
import pe.axiz.payments.history.application.query.GetPaymentHandler;
import pe.axiz.payments.history.application.query.GetPaymentQuery;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsHandler;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsQuery;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;

/** The REST adapter over the real query handlers: filters, masking and missing data. */
@ExtendWith(BlockHoundExtension.class)
class PaymentHistoryControllerTest {

  static final Duration T = Duration.ofSeconds(5);
  static final Instant AT = Instant.parse("2026-10-09T15:00:00Z");

  final InMemoryPaymentHistories histories = new InMemoryPaymentHistories();
  final GetPaymentHandler get = new GetPaymentHandler(histories);
  final ListCustomerPaymentsHandler list = new ListCustomerPaymentsHandler(histories);
  final PaymentHistoryController controller =
      new PaymentHistoryController(
          new ReactiveQueryBus() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> Mono<R> ask(Query<R> query) {
              return switch (query) {
                case GetPaymentQuery q -> (Mono<R>) get.handle(q);
                case ListCustomerPaymentsQuery q -> (Mono<R>) list.handle(q);
                default -> Mono.error(new IllegalArgumentException(query.toString()));
              };
            }
          });

  void registered(String id, Instant at) {
    histories.entries.put(
        id,
        PaymentHistory.registered(
            null,
            id,
            "C-1",
            new PaymentHistory.Registration(
                "OP-" + id,
                "00219100123456789053",
                Money.of("150.75", "PEN"),
                LocalDate.of(2026, 10, 9)),
            at));
  }

  @Test
  void aPaymentLeavesWithItsAccountMasked() {
    registered("P-1", AT);

    var response = controller.getPayment("P-1").block(T);

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    PaymentHistoryItem item = response.getBody();
    assertThat(item).isNotNull();
    assertThat(item.getStatus()).isEqualTo(PaymentStatus.RECEIVED);
    assertThat(item.getCreditorCci()).endsWith("9053").doesNotContain("00219100123456789053");
    assertThat(item.getAmount()).isEqualByComparingTo("150.75");
    assertThat(item.getCurrency()).isEqualTo("PEN");
    assertThat(item.getLastChangeAt()).isEqualTo(AT.atOffset(ZoneOffset.UTC));
    assertThatThrownBy(() -> controller.getPayment("P-404").block(T))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aStatusSeenBeforeTheRegistrationHasNoAmount() {
    histories.entries.put(
        "P-9",
        PaymentHistory.statusChanged(
            null,
            "P-9",
            "C-1",
            pe.axiz.payments.history.domain.PaymentStatus.PROCESSING,
            null,
            AT));

    PaymentHistoryItem item = controller.getPayment("P-9").block(T).getBody();

    assertThat(item).isNotNull();
    assertThat(item.getAmount()).isNull();
    assertThat(item.getCurrency()).isNull();
    assertThat(item.getCreditorCci()).isNull();
  }

  @Test
  void listsAreLatestFirstAndCanBeFilteredByStatus() {
    registered("P-1", AT);
    registered("P-2", AT.plusSeconds(60));
    histories.entries.computeIfPresent(
        "P-1",
        (_, h) ->
            PaymentHistory.statusChanged(
                h,
                "P-1",
                "C-1",
                pe.axiz.payments.history.domain.PaymentStatus.REJECTED,
                "limit",
                AT.plusSeconds(120)));

    PaymentHistoryPage all =
        controller.listCustomerPayments("C-1", null, PageRequest.of(0, 20)).block(T).getBody();
    PaymentHistoryPage rejected =
        controller
            .listCustomerPayments("C-1", "REJECTED", PageRequest.of(0, 20))
            .block(T)
            .getBody();

    assertThat(all).isNotNull();
    assertThat(all.getItems())
        .extracting(PaymentHistoryItem::getPaymentId)
        .containsExactly("P-1", "P-2");
    assertThat(all.getTotalElements()).isEqualTo(2L);
    assertThat(all.getPage()).isZero();
    assertThat(all.getSize()).isEqualTo(20);
    assertThat(rejected).isNotNull();
    assertThat(rejected.getItems())
        .extracting(PaymentHistoryItem::getReason)
        .containsExactly("limit");
  }

  @Test
  void anUnknownStatusIsAValidationError() {
    assertThatThrownBy(() -> controller.listCustomerPayments("C-1", "LOST", PageRequest.first()))
        .isInstanceOfSatisfying(
            ValidationException.class,
            e ->
                assertThat(e.violations())
                    .singleElement()
                    .satisfies(v -> assertThat(v.toString()).contains("status")));
  }
}

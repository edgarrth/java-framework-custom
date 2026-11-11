package pe.axiz.payments.history.application.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.money.Money;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.payments.history.application.InMemoryPaymentHistories;
import pe.axiz.payments.history.application.query.GetPaymentQuery;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsQuery;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.spring.test.BlockHoundExtension;

/** Who may read the payment history: the owner, or a service with payments:read. */
@ExtendWith(BlockHoundExtension.class)
class HistoryAccessTest {

  static final Duration T = Duration.ofSeconds(5);

  final InMemoryPaymentHistories histories = new InMemoryPaymentHistories();
  final HistoryAccess.GetPolicy get = new HistoryAccess.GetPolicy(histories);
  final HistoryAccess.ListPolicy list = new HistoryAccess.ListPolicy();

  static Caller customer(String id) {
    return new Caller(id, "payments-ux", null, null, Set.of());
  }

  static Caller service(String... authorities) {
    return new Caller(null, "reporting", null, null, Set.of(authorities));
  }

  HistoryAccessTest() {
    histories.entries.put(
        "p-1",
        PaymentHistory.registered(
            null,
            "p-1",
            "C-1001",
            new PaymentHistory.Registration(
                "OP-1", "00219100123456789053", Money.of("10", "PEN"), LocalDate.of(2026, 10, 9)),
            Instant.parse("2026-10-09T15:00:00Z")));
  }

  @Test
  void theOwnerAndReadingServicesSeeAPayment() {
    GetPaymentQuery query = new GetPaymentQuery("p-1");
    assertThatCode(() -> get.check(query, customer("C-1001")).block(T)).doesNotThrowAnyException();
    assertThatCode(() -> get.check(query, service(HistoryAccess.READ)).block(T))
        .doesNotThrowAnyException();
  }

  /** Another customer's payment and a missing one look the same: ids cannot be probed. */
  @Test
  void othersGetNotFound() {
    assertThatThrownBy(() -> get.check(new GetPaymentQuery("p-1"), customer("C-2002")).block(T))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> get.check(new GetPaymentQuery("p-1"), service()).block(T))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> get.check(new GetPaymentQuery("p-9"), customer("C-1001")).block(T))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void customersListOnlyTheirOwnHistory() {
    assertThatCode(
            () ->
                list.check(
                        new ListCustomerPaymentsQuery("C-1001", null, PageRequest.of(0, 20)),
                        customer("C-1001"))
                    .block(T))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                list.check(
                        new ListCustomerPaymentsQuery("C-1001", null, PageRequest.of(0, 20)),
                        customer("C-2002"))
                    .block(T))
        .isInstanceOf(AccessException.class);
  }
}

package pe.axiz.payments.history.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.payments.history.application.command.RecordPaymentRegisteredCommand;
import pe.axiz.payments.history.application.command.RecordPaymentRegisteredHandler;
import pe.axiz.payments.history.application.command.RecordPaymentStatusChangedCommand;
import pe.axiz.payments.history.application.command.RecordPaymentStatusChangedHandler;
import pe.axiz.payments.history.application.query.GetPaymentHandler;
import pe.axiz.payments.history.application.query.GetPaymentQuery;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsHandler;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsQuery;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import pe.axiz.spring.test.BlockHoundExtension;

@ExtendWith(BlockHoundExtension.class)
class ProjectionHandlersTest {

  private static final Duration T = Duration.ofSeconds(5);
  private static final Instant T0 = Instant.parse("2026-10-11T15:00:00Z");

  private final InMemoryPaymentHistories store = new InMemoryPaymentHistories();
  private final RecordPaymentRegisteredHandler registered =
      new RecordPaymentRegisteredHandler(store);
  private final RecordPaymentStatusChangedHandler changed =
      new RecordPaymentStatusChangedHandler(store);

  private void register(String id, String customer, Instant at) {
    registered
        .handle(
            new RecordPaymentRegisteredCommand(
                id,
                "OP-" + id,
                customer,
                "00219100123456789053",
                new BigDecimal("150.75"),
                "PEN",
                LocalDate.parse("2026-10-12"),
                at))
        .block(T);
  }

  @Test
  void eventsBuildTheReadModelIdempotently() {
    register("p-1", "C-1001", T0);
    register("p-1", "C-1001", T0); // reentrega
    changed
        .handle(
            new RecordPaymentStatusChangedCommand(
                "p-1", "C-1001", PaymentStatus.COMPLETED, "Settled", T0.plusSeconds(2)))
        .block(T);
    PaymentHistory h = new GetPaymentHandler(store).handle(new GetPaymentQuery("p-1")).block(T);
    assertThat(h).isNotNull();
    assertThat(h.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(store.entries).hasSize(1);
  }

  @Test
  void anInexactEventIsNotWrittenToTheReadModel() {
    var command =
        new RecordPaymentRegisteredCommand(
            "invalid",
            "OP-invalid",
            "C-1001",
            "00219100123456789053",
            new BigDecimal("150.005"),
            "PEN",
            LocalDate.parse("2026-10-12"),
            T0);
    assertThatThrownBy(() -> registered.handle(command).block(T))
        .isInstanceOf(pe.axiz.core.errors.BusinessException.class);
    assertThat(store.entries).isEmpty();
  }

  @Test
  void unknownPaymentsAreNotFound() {
    assertThatThrownBy(() -> new GetPaymentHandler(store).handle(new GetPaymentQuery("x")).block(T))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void customersSeeTheirPaymentsLatestFirst() {
    register("p-1", "C-1001", T0);
    register("p-2", "C-1001", T0.plusSeconds(10));
    register("p-3", "C-2002", T0.plusSeconds(20));
    changed
        .handle(
            new RecordPaymentStatusChangedCommand(
                "p-1", "C-1001", PaymentStatus.REJECTED, "Blocked account", T0.plusSeconds(30)))
        .block(T);
    ListCustomerPaymentsHandler list = new ListCustomerPaymentsHandler(store);
    Page<PaymentHistory> all =
        list.handle(new ListCustomerPaymentsQuery("C-1001", null, PageRequest.of(0, 20))).block(T);
    assertThat(all).isNotNull();
    assertThat(all.content()).extracting(PaymentHistory::paymentId).containsExactly("p-1", "p-2");
    Page<PaymentHistory> rejected =
        list.handle(
                new ListCustomerPaymentsQuery(
                    "C-1001", PaymentStatus.REJECTED, PageRequest.of(0, 20)))
            .block(T);
    assertThat(rejected).isNotNull();
    assertThat(rejected.metadata().totalElements()).isEqualTo(1);
  }
}

package pe.axiz.payments.orders.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.money.Money;
import pe.axiz.payments.orders.application.port.out.PaymentEvents;
import pe.axiz.payments.orders.domain.PaymentErrors;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;

/** Status changes: retries, the daily limit given back on rejection and the published events. */
class ChangePaymentOrderStatusHandlerTest {

  static final LocalDate DAY = LocalDate.of(2026, 10, 9);

  final InMemoryPaymentOrders orders = new InMemoryPaymentOrders();
  final List<String> published = new ArrayList<>();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final ChangePaymentOrderStatusHandler handler =
      new ChangePaymentOrderStatusHandler(
          orders,
          new PaymentEvents() {
            @Override
            public void created(PaymentOrder order) {
              published.add("created");
            }

            @Override
            public void statusChanged(PaymentOrder order, PaymentStatus previous) {
              published.add(previous + "->" + order.status());
            }
          },
          new EventMetrics(meters, MetricsSettings.defaults()));

  PaymentOrder stored(String id) {
    PaymentOrder order =
        PaymentOrder.register(
            id,
            "OP-" + id,
            "C-1",
            "1910012345678",
            "00219100123456789053",
            Money.of("150.75", "PEN"),
            "Rent",
            DAY,
            Instant.parse("2026-10-09T15:00:00Z"));
    orders.create(order, null);
    orders.reserveDaily("C-1", order.amount(), DAY, Money.of("1000", "PEN"));
    return order;
  }

  double finished(String outcome) {
    return meters.getMeters().stream()
        .filter(m -> "payment.order.finished".equals(m.getId().getTag("event")))
        .filter(m -> outcome.equals(m.getId().getTag("outcome")))
        .mapToDouble(m -> m.measure().iterator().next().getValue())
        .sum();
  }

  @Test
  void processingAndCompletionArePublishedAndTheEndIsMeasured() {
    stored("P-1");

    handler.handle(new ChangePaymentOrderStatusCommand("P-1", PaymentStatus.PROCESSING, null));
    assertThat(finished("success")).isZero();
    PaymentOrder done =
        handler.handle(new ChangePaymentOrderStatusCommand("P-1", PaymentStatus.COMPLETED, "ok"));

    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.statusReason()).isEqualTo("ok");
    assertThat(published).containsExactly("RECEIVED->PROCESSING", "PROCESSING->COMPLETED");
    assertThat(orders.reservedOn("C-1", "PEN", DAY)).isEqualTo(Money.of("150.75", "PEN"));
    assertThat(finished("success")).isPositive();
  }

  @Test
  void aRejectionGivesTheAmountBackToTheDailyLimit() {
    stored("P-2");

    handler.handle(
        new ChangePaymentOrderStatusCommand("P-2", PaymentStatus.REJECTED, "insufficient funds"));

    assertThat(orders.find("P-2").orElseThrow().status()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(orders.reservedOn("C-1", "PEN", DAY).isZero()).isTrue();
    assertThat(published).containsExactly("RECEIVED->REJECTED");
    assertThat(finished("rejected")).isPositive();
  }

  @Test
  void aRetriedChangeIsAppliedOnce() {
    stored("P-3");
    handler.handle(new ChangePaymentOrderStatusCommand("P-3", PaymentStatus.PROCESSING, null));

    PaymentOrder again =
        handler.handle(new ChangePaymentOrderStatusCommand("P-3", PaymentStatus.PROCESSING, null));

    assertThat(again.status()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(published).containsExactly("RECEIVED->PROCESSING");
  }

  @Test
  void unknownOrdersAndForbiddenTransitionsAreRefused() {
    assertThatThrownBy(
            () ->
                handler.handle(
                    new ChangePaymentOrderStatusCommand("P-404", PaymentStatus.COMPLETED, null)))
        .isInstanceOf(NotFoundException.class);

    stored("P-4");
    assertThatThrownBy(
            () ->
                handler.handle(
                    new ChangePaymentOrderStatusCommand("P-4", PaymentStatus.COMPLETED, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(PaymentErrors.TRANSITION_NOT_ALLOWED));
    assertThat(published).isEmpty();
  }
}

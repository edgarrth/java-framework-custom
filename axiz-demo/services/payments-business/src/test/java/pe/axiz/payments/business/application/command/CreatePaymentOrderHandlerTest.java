package pe.axiz.payments.business.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.money.Money;
import pe.axiz.core.time.BusinessCalendar;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.payments.business.application.port.out.PaymentEvents;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.payments.business.domain.PaymentErrors;
import pe.axiz.payments.business.domain.PaymentLimits;
import pe.axiz.payments.business.domain.PaymentOrder;
import pe.axiz.payments.business.domain.PaymentStatus;

class CreatePaymentOrderHandlerTest {

  static final ZoneId LIMA = ZoneId.of("America/Lima");

  /** In-memory port. */
  static final class InMemoryOrders implements PaymentOrders {
    final Map<String, PaymentOrder> rows = new HashMap<>();
    final Map<String, String> requestKeys = new HashMap<>();
    final Map<String, Money> reserved = new HashMap<>();

    @Override
    public void create(PaymentOrder order, @Nullable String requestKey) {
      rows.put(order.id(), order);
      if (requestKey != null) {
        requestKeys.put(order.customerId() + "|" + requestKey, order.id());
      }
    }

    @Override
    public Optional<PaymentOrder> findByRequestKey(String customerId, String requestKey) {
      return Optional.ofNullable(requestKeys.get(customerId + "|" + requestKey)).map(rows::get);
    }

    @Override
    public boolean reserveDaily(String customerId, Money amount, LocalDate date, Money limit) {
      String key = customerId + "|" + amount.currencyCode() + "|" + date;
      Money total = reserved.getOrDefault(key, Money.zero(amount.currencyCode())).plus(amount);
      if (total.isGreaterThan(limit)) {
        return false;
      }
      reserved.put(key, total);
      return true;
    }

    @Override
    public void releaseDaily(String customerId, Money amount, LocalDate date) {
      reserved.computeIfPresent(
          customerId + "|" + amount.currencyCode() + "|" + date, (k, v) -> v.minus(amount));
    }

    @Override
    public Money reservedOn(String customerId, String currency, LocalDate date) {
      return reserved.getOrDefault(customerId + "|" + currency + "|" + date, Money.zero(currency));
    }

    @Override
    public void save(PaymentOrder order) {
      rows.put(order.id(), order);
    }

    @Override
    public Optional<PaymentOrder> find(String id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Page<PaymentOrder> byCustomer(String customerId, PageRequest page) {
      List<PaymentOrder> all =
          rows.values().stream().filter(o -> o.customerId().equals(customerId)).toList();
      return Page.of(all, page, all.size());
    }

    @Override
    public boolean exists(String customerId, String cci, Money amount, LocalDate date) {
      return rows.values().stream()
          .anyMatch(
              o ->
                  o.customerId().equals(customerId)
                      && o.creditorCci().equals(cci)
                      && o.amount().equals(amount)
                      && o.processingDate().equals(date));
    }
  }

  final InMemoryOrders orders = new InMemoryOrders();
  final List<String> published = new ArrayList<>();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final AtomicInteger sequence = new AtomicInteger();

  CreatePaymentOrderHandler handler(Instant now) {
    Clock clock = Clock.fixed(now, LIMA);
    BusinessCalendar calendar = BusinessCalendar.builder(LIMA).build();
    PaymentEvents events =
        new PaymentEvents() {
          @Override
          public void created(PaymentOrder order) {
            published.add("created:" + order.operationNumber());
          }

          @Override
          public void statusChanged(PaymentOrder order, PaymentStatus previous) {
            published.add("status:" + order.status());
          }
        };
    return new CreatePaymentOrderHandler(
        orders,
        () -> "OP-" + sequence.incrementAndGet(),
        events,
        new PaymentLimits(Map.of("PEN", Money.of("1000", "PEN"))),
        new CutoffTime(LocalTime.of(18, 0), calendar),
        IdentifierRegistry.withDiscoveredTypes(),
        () -> "id-" + sequence.get(),
        new EventMetrics(meters, MetricsSettings.defaults()),
        clock);
  }

  static CreatePaymentOrderCommand command(String amount) {
    return command(amount, null);
  }

  static CreatePaymentOrderCommand command(String amount, @Nullable String requestKey) {
    return new CreatePaymentOrderCommand(
        "C-1",
        "1910012345678",
        "00219100123456789053",
        new BigDecimal(amount),
        "PEN",
        "Rent",
        requestKey);
  }

  @Test
  void registersAndPublishes() {
    // Friday 2026-10-09 10:00 Lima: before the cut-off, same business day
    PaymentOrder o = handler(Instant.parse("2026-10-09T15:00:00Z")).handle(command("150.75"));
    assertThat(o.status()).isEqualTo(PaymentStatus.RECEIVED);
    assertThat(o.processingDate()).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(published).containsExactly("created:OP-1");
    assertThat(orders.rows).containsKey(o.id());
  }

  @Test
  void afterTheCutoffMovesToTheNextBusinessDay() {
    // Friday 19:00 Lima -> Monday
    PaymentOrder o = handler(Instant.parse("2026-10-10T00:00:00Z")).handle(command("10"));
    assertThat(o.processingDate()).isEqualTo(LocalDate.of(2026, 10, 12));
  }

  @Test
  void rejectsProbableDuplicates() {
    CreatePaymentOrderHandler h = handler(Instant.parse("2026-10-09T15:00:00Z"));
    h.handle(command("100"));
    assertThatThrownBy(() -> h.handle(command("100")))
        .extracting(e -> ((BusinessException) e).definition())
        .isEqualTo(PaymentErrors.POSSIBLE_DUPLICATE);
  }

  @Test
  void rejectsWhatDoesNotFitInTheDailyLimitWithTheAvailableAmount() {
    CreatePaymentOrderHandler h = handler(Instant.parse("2026-10-09T15:00:00Z"));
    h.handle(command("600"));
    BusinessException e =
        (BusinessException)
            org.assertj.core.api.Assertions.catchThrowable(() -> h.handle(command("500")));
    assertThat(e.definition()).isEqualTo(PaymentErrors.DAILY_LIMIT_EXCEEDED);
    assertThat(e.parameters()).containsEntry("available", "400.00");
    assertThatThrownBy(() -> h.handle(command("1000.01")))
        .extracting(x -> ((BusinessException) x).definition())
        .isEqualTo(PaymentErrors.DAILY_LIMIT_EXCEEDED);
  }

  /** Report case (F12): a retried request returns the order it created, never a second one. */
  @Test
  void aRetriedRequestReturnsTheSameOrderAndReservesOnce() {
    CreatePaymentOrderHandler h = handler(Instant.parse("2026-10-09T15:00:00Z"));
    PaymentOrder first = h.handle(command("300", "key-1"));
    PaymentOrder retry = h.handle(command("300", "key-1"));
    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(orders.rows).hasSize(1);
    assertThat(orders.reservedOn("C-1", "PEN", first.processingDate()))
        .isEqualTo(Money.of("300", "PEN"));
    assertThatThrownBy(() -> h.handle(command("301", "key-1")))
        .extracting(x -> ((BusinessException) x).definition())
        .isEqualTo(PaymentErrors.REQUEST_KEY_REUSED);
  }

  @Test
  void rejectsInvalidCreditorAccounts() {
    CreatePaymentOrderHandler h = handler(Instant.parse("2026-10-09T15:00:00Z"));
    CreatePaymentOrderCommand badCci =
        new CreatePaymentOrderCommand(
            "C-1", "1910012345678", "12345678901234567890", BigDecimal.TEN, "PEN", null, null);
    assertThatThrownBy(() -> h.handle(badCci))
        .extracting(e -> ((BusinessException) e).definition())
        .isEqualTo(PaymentErrors.INVALID_CREDITOR_ACCOUNT);
  }
}

package pe.axiz.payments.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;

class PaymentOrderTest {

  private static PaymentOrder order() {
    return PaymentOrder.register(
        "id-1",
        "OP20261011-00000001",
        "C-1",
        "1910012345678",
        "00219100123456789053",
        Money.of("150.75", "PEN"),
        "Rent",
        LocalDate.of(2026, 10, 12),
        Instant.parse("2026-10-11T15:00:00Z"));
  }

  @Test
  void followsItsLifecycle() {
    PaymentOrder o = order();
    assertThat(o.status()).isEqualTo(PaymentStatus.RECEIVED);
    o.moveTo(PaymentStatus.PROCESSING, null);
    o.moveTo(PaymentStatus.COMPLETED, "settled");
    assertThat(o.status().isFinal()).isTrue();
    assertThatThrownBy(() -> o.moveTo(PaymentStatus.REJECTED, "late"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).definition())
        .isEqualTo(PaymentErrors.TRANSITION_NOT_ALLOWED);
  }

  @Test
  void masksTheCreditorAccount() {
    assertThat(order().maskedCreditorCci()).isEqualTo("****************9053");
  }

  @Test
  void limitsArePerDayAndCurrency() {
    PaymentLimits limits = new PaymentLimits(Map.of("PEN", Money.of("1000", "PEN")));
    limits.check(Money.of("800", "PEN"), Money.of("200", "PEN"));
    assertThatThrownBy(() -> limits.check(Money.of("800", "PEN"), Money.of("200.01", "PEN")))
        .extracting(e -> ((BusinessException) e).definition())
        .isEqualTo(PaymentErrors.DAILY_LIMIT_EXCEEDED);
    assertThatThrownBy(() -> limits.check(Money.zero("USD"), Money.of("1", "USD")))
        .extracting(e -> ((BusinessException) e).definition())
        .isEqualTo(PaymentErrors.UNSUPPORTED_CURRENCY);
  }
}

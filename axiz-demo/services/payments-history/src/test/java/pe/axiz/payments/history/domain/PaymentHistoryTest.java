package pe.axiz.payments.history.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import pe.axiz.core.money.Money;

class PaymentHistoryTest {

  private static final Instant T0 = Instant.parse("2026-10-11T15:00:00Z");
  private static final PaymentHistory.Registration REGISTRATION =
      new PaymentHistory.Registration(
          "OP20261011-00000001",
          "00219100123456789053",
          Money.of("150.75", "PEN"),
          LocalDate.parse("2026-10-12"));

  @Test
  void registrationStartsTheEntry() {
    PaymentHistory h = PaymentHistory.registered(null, "p-1", "C-1001", REGISTRATION, T0);
    assertThat(h.status()).isEqualTo(PaymentStatus.RECEIVED);
    assertThat(h.maskedCreditorCci()).isEqualTo("****************9053");
    assertThat(h.toString()).doesNotContain("9053");
  }

  @Test
  void statusChangesMoveForwardOnly() {
    PaymentHistory h = PaymentHistory.registered(null, "p-1", "C-1001", REGISTRATION, T0);
    PaymentHistory processing =
        PaymentHistory.statusChanged(
            h, "p-1", "C-1001", PaymentStatus.PROCESSING, null, T0.plusSeconds(1));
    PaymentHistory completed =
        PaymentHistory.statusChanged(
            processing, "p-1", "C-1001", PaymentStatus.COMPLETED, "Settled", T0.plusSeconds(3));
    assertThat(completed.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(completed.reason()).isEqualTo("Settled");

    PaymentHistory late =
        PaymentHistory.statusChanged(
            completed, "p-1", "C-1001", PaymentStatus.PROCESSING, null, T0.plusSeconds(2));
    assertThat(late).isSameAs(completed);
    PaymentHistory redelivered =
        PaymentHistory.statusChanged(
            completed, "p-1", "C-1001", PaymentStatus.PROCESSING, null, T0.plusSeconds(9));
    assertThat(redelivered.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void aStatusSeenBeforeTheRegistrationIsKept() {
    PaymentHistory partial =
        PaymentHistory.statusChanged(
            null, "p-1", "C-1001", PaymentStatus.REJECTED, "Insufficient funds", T0.plusSeconds(5));
    assertThat(partial.amount()).isNull();
    assertThat(partial.maskedCreditorCci()).isNull();
    PaymentHistory complete = PaymentHistory.registered(partial, "p-1", "C-1001", REGISTRATION, T0);
    assertThat(complete.status()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(complete.reason()).isEqualTo("Insufficient funds");
    assertThat(complete.amount()).isEqualTo(Money.of("150.75", "PEN"));
    assertThat(complete.lastChangeAt()).isEqualTo(T0.plusSeconds(5));
  }
}

package pe.axiz.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import pe.axiz.core.errors.BusinessException;

/**
 * Exchange rate valid at an instant: {@code 1 from = rate to}.
 *
 * @param from source currency
 * @param to target currency
 * @param rate positive rate
 * @param validAt instant at which it applies
 */
public record ExchangeRate(Currency from, Currency to, BigDecimal rate, Instant validAt) {

  public ExchangeRate {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(validAt, "validAt");
    if (rate == null || rate.signum() <= 0) {
      throw new IllegalArgumentException("rate must be positive");
    }
  }

  /**
   * Converts an amount in the source currency.
   *
   * @param money amount in {@link #from()}
   * @return the amount in {@link #to()}
   */
  public Money convert(Money money) {
    if (!money.currency().equals(from)) {
      throw new BusinessException(MoneyErrors.CURRENCY_MISMATCH, "rate does not apply");
    }
    return Money.of(money.amount().multiply(rate), to, Money.DEFAULT_ROUNDING);
  }

  /** Inverse rate (to → from) with 10 decimals. */
  public ExchangeRate inverse() {
    return new ExchangeRate(
        to, from, BigDecimal.ONE.divide(rate, 10, RoundingMode.HALF_EVEN), validAt);
  }
}

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
 * @param rate positive rate within Money's integer-digit and scale limits
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
    // Bound the rate before inverse division can expand an attacker-controlled exponent.
    Money.requireBounded(rate);
  }

  /**
   * Converts an amount in the source currency.
   *
   * @param money amount in {@link #from()}
   * @param rounding caller-selected rounding mode
   * @return the amount in {@link #to()}
   */
  public Money convert(Money money, RoundingMode rounding) {
    Objects.requireNonNull(rounding, "rounding");
    if (!money.currency().equals(from)) {
      throw new BusinessException(MoneyErrors.CURRENCY_MISMATCH, "rate does not apply");
    }
    return Money.rounded(money.amount().multiply(rate), to, rounding);
  }

  /**
   * Converts an amount in {@link #to()} to {@link #from()} with one division and one rounding. No
   * approximate reciprocal rate is created.
   */
  public Money convertInverse(Money money, RoundingMode rounding) {
    Objects.requireNonNull(rounding, "rounding");
    if (!money.currency().equals(to)) {
      throw new BusinessException(MoneyErrors.CURRENCY_MISMATCH, "inverse rate does not apply");
    }
    return Money.of(money.amount().divide(rate, Money.scaleOf(from), rounding), from);
  }

  /**
   * Represents an approximate reciprocal rate at the caller-selected scale and rounding mode. Use
   * {@link #convertInverse(Money, RoundingMode)} to convert amounts without double rounding.
   */
  public ExchangeRate inverse(int scale, RoundingMode rounding) {
    Objects.requireNonNull(rounding, "rounding");
    if (scale < 0 || scale > Money.MAX_SCALE) {
      throw new IllegalArgumentException("inverse scale must be between 0 and " + Money.MAX_SCALE);
    }
    return new ExchangeRate(to, from, BigDecimal.ONE.divide(rate, scale, rounding), validAt);
  }
}

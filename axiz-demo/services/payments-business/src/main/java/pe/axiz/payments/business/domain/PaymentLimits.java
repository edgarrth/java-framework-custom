package pe.axiz.payments.business.domain;

import java.util.Map;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;

/**
 * Daily limit per currency of the payments of a customer.
 *
 * @param daily maximum amount per day and currency code
 */
public record PaymentLimits(Map<String, Money> daily) {

  public PaymentLimits {
    daily = Map.copyOf(daily);
  }

  /**
   * Checks that {@code amount} fits in the limit given what was already paid today.
   *
   * @throws BusinessException {@link PaymentErrors#DAILY_LIMIT_EXCEEDED} or {@link
   *     PaymentErrors#UNSUPPORTED_CURRENCY}
   */
  public void check(Money alreadyPaid, Money amount) {
    Money limit = limitFor(amount);
    if (alreadyPaid.plus(amount).isGreaterThan(limit)) {
      throw exceeded(limit, alreadyPaid);
    }
  }

  /**
   * Limit of the currency of {@code amount}.
   *
   * @throws BusinessException {@link PaymentErrors#UNSUPPORTED_CURRENCY}
   */
  public Money limitFor(Money amount) {
    Money limit = daily.get(amount.currencyCode());
    if (limit == null) {
      throw new BusinessException(
          PaymentErrors.UNSUPPORTED_CURRENCY,
          "currency " + amount.currencyCode(),
          Map.of("currency", amount.currencyCode()));
    }
    return limit;
  }

  /** The error for an amount that does not fit, with what is still available. */
  public BusinessException exceeded(Money limit, Money alreadyReserved) {
    Money available = limit.minus(alreadyReserved);
    return new BusinessException(
        PaymentErrors.DAILY_LIMIT_EXCEEDED,
        "daily limit exceeded",
        Map.of(
            "limit",
            limit.amount().toPlainString(),
            "currency",
            limit.currencyCode(),
            "available",
            (available.amount().signum() < 0 ? Money.zero(limit.currencyCode()) : available)
                .amount()
                .toPlainString()));
  }
}

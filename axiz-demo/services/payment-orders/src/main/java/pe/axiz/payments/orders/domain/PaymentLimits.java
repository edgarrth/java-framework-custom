package pe.axiz.payments.orders.domain;

import java.util.Map;
import pe.axiz.core.commons.ReviewedState;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;

/**
 * Límite diario por moneda de los pagos de un cliente.
 *
 * @param daily monto máximo por día y código de moneda
 */
public record PaymentLimits(
    @ReviewedState(
            reason =
                "Map.copyOf snapshots configuration; Money and the published map are immutable")
        Map<String, Money> daily) {

  public PaymentLimits {
    daily = Map.copyOf(daily);
  }

  /**
   * Verifica que {@code amount} quepa en el límite considerando lo ya pagado hoy.
   *
   * @throws BusinessException {@link PaymentErrors#DAILY_LIMIT_EXCEEDED} o {@link
   *     PaymentErrors#UNSUPPORTED_CURRENCY}
   */
  public void check(Money alreadyPaid, Money amount) {
    Money limit = limitFor(amount);
    if (alreadyPaid.plus(amount).isGreaterThan(limit)) {
      throw exceeded(limit, alreadyPaid);
    }
  }

  /**
   * Límite de la moneda de {@code amount}.
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

  /** El error para un monto que no cabe, con lo que aún está disponible. */
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

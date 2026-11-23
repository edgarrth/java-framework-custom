package pe.axiz.core.money;

import java.math.RoundingMode;
import java.time.Clock;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.errors.BusinessException;

/**
 * Convierte montos con un {@link ExchangeRateProvider}, probando la tasa inversa como alternativa.
 */
public final class CurrencyConverter {

  private final ExchangeRateProvider provider;
  private final Clock clock;

  public CurrencyConverter(ExchangeRateProvider provider, Clock clock) {
    this.provider = Objects.requireNonNull(provider);
    this.clock = Objects.requireNonNull(clock);
  }

  public Money convert(Money money, Currency target, RoundingMode rounding) {
    Objects.requireNonNull(rounding, "rounding");
    if (money.currency().equals(target)) {
      return money;
    }
    var now = clock.instant();
    return provider
        .rate(money.currency(), target, now)
        .map(rate -> rate.convert(money, rounding))
        .or(
            () ->
                provider
                    .rate(target, money.currency(), now)
                    .map(rate -> rate.convertInverse(money, rounding)))
        .orElseThrow(
            () ->
                new BusinessException(
                    MoneyErrors.RATE_NOT_AVAILABLE,
                    "no exchange rate",
                    Map.of("from", money.currencyCode(), "to", target.getCurrencyCode())));
  }
}

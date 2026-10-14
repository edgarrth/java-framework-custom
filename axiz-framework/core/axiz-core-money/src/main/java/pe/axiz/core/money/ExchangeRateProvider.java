package pe.axiz.core.money;

import java.time.Instant;
import java.util.Currency;
import java.util.Optional;

/** Port to obtain exchange rates; implemented by the service (table, cache, external API). */
@FunctionalInterface
public interface ExchangeRateProvider {

  Optional<ExchangeRate> rate(Currency from, Currency to, Instant at);
}

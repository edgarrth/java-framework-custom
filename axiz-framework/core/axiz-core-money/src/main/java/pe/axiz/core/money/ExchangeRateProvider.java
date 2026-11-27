package pe.axiz.core.money;

import java.time.Instant;
import java.util.Currency;
import java.util.Optional;

/** Puerto para obtener tipos de cambio; lo implementa el servicio (tabla, caché, API externa). */
@FunctionalInterface
public interface ExchangeRateProvider {

  Optional<ExchangeRate> rate(Currency from, Currency to, Instant at);
}

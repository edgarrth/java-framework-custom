<!-- generated -->
# axiz-core-money

Money value object with ISO 4217 currencies, rounding and safe arithmetic; exchange rate port.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-money</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `CurrencyConverter` | Converts amounts using an `ExchangeRateProvider`, trying the inverse rate as fallback. |
| `ExchangeRate` | Exchange rate valid at an instant: `1 from = rate to`. |
| `ExchangeRateProvider` | Port to obtain exchange rates; implemented by the service (table, cache, external API). |
| `Money` | Monetary amount in an ISO 4217 currency. |
| `MoneyErrors` | Errors of the money capability. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-money verify`.

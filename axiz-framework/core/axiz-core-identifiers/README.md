<!-- generated -->
# axiz-core-identifiers

Generic identifier SPI with validators (national documents, bank accounts, cards, contact data).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-identifiers</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `CheckDigits` | Check digit algorithms shared by identifier types. |
| `Identifier` | Validated identifier value object. |
| `IdentifierRegistry` | Registry of identifier types: built-in packs, ServiceLoader providers and custom types. |
| `IdentifierType` | Service provider interface for any kind of identifier (document, account, card, contact...). |
| `IdentifierTypeProvider` | Discovered with `java.util.ServiceLoader` to register packs of identifier types. |
| `InvalidIdentifierException` | Thrown when a value is not a valid identifier of the requested type. |
| `PeruIdentifiers` | Peruvian national documents: DNI, RUC and foreigner card (CE). |
| `ContactIdentifiers` | Contact data: E.164 phone numbers and email addresses. |
| `BankingIdentifiers` | Banking identifiers: interbank account code (CCI), card number (PAN) and IBAN. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-identifiers verify`.

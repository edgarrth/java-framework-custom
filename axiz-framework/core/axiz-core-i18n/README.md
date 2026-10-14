<!-- generated -->
# axiz-core-i18n

Message resolution from multiple bundles with locale fallback.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-i18n</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `BundleMessageResolver` | Message resolver over several `ResourceBundle` base names (UTF-8 properties). |
| `CompositeMessageResolver` | Chains resolvers; the first one that knows the key wins (service before framework). |
| `LocaleResolver` | Chooses the response locale from an `Accept-Language` header among the supported ones (RFC 4647 lookup with quality values). |
| `MessageFormatter` | Replaces `{name`} placeholders. |
| `MessageResolver` | Resolves localized messages with named parameters (`{name`}). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-i18n verify`.

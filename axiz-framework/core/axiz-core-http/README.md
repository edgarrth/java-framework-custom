<!-- generated -->
# axiz-core-http

HTTP client model: per-client settings, outbound authentication, retry rules, error translation and header propagation.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-http</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AccessToken` | OAuth2 access token. |
| `AccessTokenProvider` | Supplies access tokens for outbound calls. |
| `AuthSettings` | Outbound authentication settings. |
| `AuthType` | Outbound authentication of an HTTP client. |
| `ClientCredentialsTokenProvider` | OAuth2 client credentials with cache and early renewal: the token is renewed when less than `renewBefore` remains. |
| `ClientSettings` | Settings of one logical HTTP client (`axiz.http-client.clients.<name>`). |
| `ExternalErrorTranslator` | Translates an error response from a dependency into an `ExternalServiceException`. |
| `HeaderPropagation` | Computes the headers forwarded to a dependency: correlation id and Accept-Language always, plus the client's allow-list. |
| `HttpRetryRules` | Retry decision for an outbound HTTP call under a client's settings. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-http verify`.

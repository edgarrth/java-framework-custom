<!-- generated -->
# axiz-core-security

AxizPrincipal, claim mapping, authority model, rate-limit keys and request signatures.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-security</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AuthorityMapper` | Maps scopes and roles to authority names (`SCOPE_x`, `ROLE_X`). |
| `AxizPrincipal` | Authenticated caller, built from the access token claims. |
| `ClaimMapping` | Which claims hold each principal attribute. |
| `ClaimsMapper` | Maps validated token claims to an `AxizPrincipal` according to a `ClaimMapping`. |
| `InMemoryRateLimiter` | Fixed-window rate limiter kept in memory: for local use, tests and single-replica services. |
| `IssuerPolicy` | Allow-list of trusted issuers. |
| `RateLimitKeys` | Builds rate-limit keys from the authenticated principal. |
| `RateLimiter` | Rate limiter port; the key always comes from the validated token, never from a client header. |
| `RequestSignature` | HMAC-SHA256 request signatures for partner callbacks and webhooks. |
| `TrustedIssuer` | An issuer the service accepts. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-security verify`.

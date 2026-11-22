<!-- generated -->
# axiz-security-spring-boot-autoconfigure

OAuth2 resource server with issuer allow-list, claim mapping, method security, headers and rate limit (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-security-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AudienceValidator` | Accepts a token only when one of its audiences is configured for its issuer. |
| `AxizAuthentication` | Authentication backed by a validated JWT whose principal is an `AxizPrincipal`, so controllers can declare `@AuthenticationPrincipal AxizPrincipal caller`. |
| `AxizJwtConverter` | Maps a validated JWT to an `AxizAuthentication` with scope and role authorities. |
| `AxizSecurityAutoConfiguration` | OAuth2 resource server for servlet and reactive services: issuer allow-list with per-issuer JWKS, audience and algorithm checks, claims mapped to `AxizPrincipal`, method security, security headers, protected actuator, problem responses for 401/403/429 and optional per-caller rate limits. |
| `AxizSecurityProperties` | Resource server settings (`axiz.security.*`). |
| `CallerContextFilter` | Adds the authenticated caller (client, subject, tenant) to the request context after token validation, so logs, audit, metrics and outbound calls see it. |
| `CallerContextWebFilter` | Reactive counterpart of `CallerContextFilter`: enriches the Reactor context. |
| `ProblemAuthenticationEntryPoint` | Servlet 401 and 403 handling: problem body plus `WWW-Authenticate: Bearer`. |
| `RateLimitFilter` | Per-caller rate limit after authentication. |
| `RateLimitRules` | Resolves the rate-limit rule and key of a request; the key always comes from the token. |
| `RateLimitWebFilter` | Reactive rate limit; the limiter call runs off the event loop. |
| `ReactiveProblemHandlers` | Reactive 401 and 403 handling with problem bodies. |
| `SecurityProblems` | Renders 401, 403 and 429 responses as RFC 9457 problems with catalog codes. |
| `TrustedIssuerDecoders` | One decoder per trusted issuer, created lazily and reused. |

Related types: `AxizSecurityRedisAutoConfiguration` live in `axiz-security-redis-spring-boot-autoconfigure`.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-security-spring-boot-autoconfigure verify`.

## Effective authorization coverage

Coverage classifies the effective `@PreAuthorize` expression structurally, using the same annotation
scanner as Spring Security (including annotation templates). Startup never evaluates SpEL or calls
application policy beans. Constants and authentication predicates alone do not establish a permission
barrier. To declare an endpoint that requires only non-anonymous authentication, use
`pe.axiz.core.security.AuthenticatedAccess`; Axiz installs a prior interceptor in both stacks.
It accepts authenticated JWTs without authorities and remember-me authentication, and rejects anonymous
access. It cannot be combined with `@PublicEndpoint`, a public path, or an always-true expression.

The supported grammar includes boolean literals, `and`, `or`, `not`, `permitAll()`, `denyAll()`, the
four authentication predicates, literal role/authority checks, `hasPermission(...)`, and
`@bean.method(...)` policies with literal, principal, authentication or variable/property arguments.
Comparisons, constructors, type references, indexing, assignments and arbitrary method chains fail
startup even in development `warn` mode. Negative authority checks are exclusions and do not alone
prove permission coverage. Dynamic policies remain the application's responsibility and require
negative authorization tests. `@Secured` attributes are literal authorities, not legacy voter tokens.

HTTP integration tests include the error handlers: unauthenticated method denials return 401 in
both stacks; authenticated callers lacking the required permissions receive 403. An effective
explicit HTTP rule or public declaration remains an independent coverage source.

At application-ready time each MVC/WebFlux business endpoint needs a prior authorization rule.
The framework checks the mapped bean's own proxy and pointcut: an annotation or a global advisor
that is not attached to that proxy does not count. `@PostAuthorize` alone does not count.
`@PreAuthorize`, enabled `@Secured`/`@RolesAllowed`, and specific rules compiled from
`axiz.security.authorization.rules` are supported. Required authorities within a rule are ANDed.
Default `anyRequest().authenticated()` does not declare endpoint permission.

A `@PublicEndpoint` additionally requires an effective matching permit-all path from
`axiz.security.public-paths`. GET endpoints also need authorization of their implicit HEAD route.
`axiz.security.authorization.unprotected=fail` is the default; `warn` is for development only.
Spring Boot/springdoc infrastructure mappings are excluded from the business coverage scan and
keep their own configured HTTP security policies.

Custom filter chains may contain arbitrary authorization managers; the framework cannot certify
their private DSL state from configuration declarations. With a custom chain, declare effective
prior method authorization on business endpoints. Property-based HTTP coverage is trusted only
when the actual installed chain is the framework chain. Tests should verify denials before any
application invocation, in addition to startup coverage.

CQRS `axiz.cqrs.authorization.unprotected` also defaults to `fail`. Bus coverage is a declaration
check; permissions on a particular resource remain the service's responsibility.

## Rate-limit outage policy

Every rule must explicitly declare `axiz.security.rate-limit.rules[].fail-open` when rate limiting
is enabled; there is no global outage policy. A missing rule policy fails startup.
`false` returns a 503 Problem if the store is unavailable; `true` allows the request. Exceeding a
healthy store's quota still returns 429. The framework never interprets an outage as quota exhaustion.

### Reactive problem rendering

Synchronous message resolvers run on the context-owned `axizSecurityRenderingScheduler`:
four workers and 64 queued tasks per worker, isolated from Reactor's shared pool. Override this
named bean for measured capacity requirements; Spring disposes it at shutdown. These internal
schedulers are not default autowiring candidates. Manual construction requires an explicit
application-owned scheduler. Saturation returns a static RFC 9457 HTTP 503 with `no-store` and
`Retry-After` of at least one second, preserving a longer quota or issuer pause already set on
the response, without invoking the resolver on the event loop. The 503 fallback removes
`WWW-Authenticate` because saturation is not an authentication or authorization decision.

`ReactiveRenderContext` takes request metadata from Reactor, then the exchange, and the current
Observation from Reactor's observation key. It never imports identity or trace IDs from the
subscribing thread or a residual worker context. It clears MDC, opens that observation's scope,
installs validated correlation/request IDs for rendering, and restores prior worker state in
`finally`, including resolver failures. HTTP response writing follows the render scope.

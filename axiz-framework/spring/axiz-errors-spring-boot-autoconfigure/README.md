<!-- generated -->
# axiz-errors-spring-boot-autoconfigure

RFC 9457 error responses with the externalized error catalog for servlet and reactive stacks (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-errors-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizErrorsAutoConfiguration` | RFC 9457 error responses for servlet and reactive applications, backed by the error catalog. |
| `AxizErrorsProperties` | Error handling settings (`axiz.errors.*`). |
| `ProblemControllerAdvice` | Converts every exception raised by MVC handlers into an RFC 9457 response. |
| `ProblemErrorController` | Replaces Boot's whitelabel error controller: errors raised in filters or by the container are rendered as RFC 9457 problems too. |
| `ProblemResponder` | Turns failures into problems and logs bounded diagnostic metadata: server errors at ERROR, failed dependencies and denied access at WARN, conflicts at INFO and other client errors at DEBUG. It never logs exception messages, throwable objects or request paths. |
| `ProblemWebExceptionHandler` | WebFlux exception handler producing RFC 9457 problems (runs before Boot's default handler). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-errors-spring-boot-autoconfigure verify`.

### Reactive problem rendering

Synchronous message resolvers run on the context-owned `axizErrorRenderingScheduler`:
four workers and 64 queued tasks per worker, isolated from Reactor's shared pool. Override this
named bean for measured capacity requirements; Spring disposes it at shutdown. These internal
schedulers are not default autowiring candidates. Manual construction requires an explicit
application-owned scheduler. Saturation returns a static RFC 9457 HTTP 503 with `no-store` and
`Retry-After` of at least one second, preserving the bounded delay of a typed
`RateLimitedException`, including wrapped failures. The fallback never invokes the resolver
on the event loop.

`ReactiveRenderContext` takes request metadata from Reactor, then the exchange, and the current
Observation from Reactor's observation key. It never imports identity or trace IDs from the
subscribing thread or a residual worker context. It clears MDC, opens that observation's scope,
installs validated correlation/request IDs for rendering, and restores prior worker state in
`finally`, including resolver failures. HTTP response writing follows the render scope.

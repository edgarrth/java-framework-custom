<!-- generated -->
# axiz-http-client-spring-boot-autoconfigure

Declarative @HttpExchange clients with per-client settings, outbound auth, resilience, error translation and logs (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-http-client-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizExchangeFilter` | Reactive counterpart of `AxizRestClientInterceptor`, without blocking: credentials that need I/O are obtained on a worker scheduler, errors are translated from the response body and retries use Reactor's backoff for idempotent calls only. |
| `AxizHttpClientAutoConfiguration` | Declarative HTTP clients (`@HttpExchange`) on Spring's HTTP service groups: every group whose name matches `axiz.http-client.clients.<name>` gets the axiz transport, timeouts, credentials, resilience, propagation, error translation and logging. |
| `AxizHttpClientProperties` | Logical HTTP clients (`axiz.http-client.clients.<name>`). |
| `AxizHttpClients` | Applies the axiz settings of a logical client to a `RestClient.Builder`: Apache HttpClient 5 transport with pool and timeouts, observation name, error translation and the axiz interceptor. |
| `AxizRestClientInterceptor` | Per-client behavior of a `RestClient`: header propagation, outbound authentication, bulkhead and circuit breaker, retries only for idempotent calls within the total timeout, and one log line per attempt (path only, never the query string). |
| `ClientNameObservationConvention` | Uses the logical client name as `client.name` in metrics and spans. |
| `ClientResilience` | Circuit breaker, bulkhead and retry decisions of one client. |
| `ExternalErrorHandler` | Translates error responses of a dependency into `ExternalServiceException` with the category chosen from the status; an empty body is preserved. |
| `HttpClientRegistry` | Validated settings and shared per-client components (resilience, credentials, transport). |
| `OutboundAuth` | Adds the outbound credentials of a client: OAuth2 client credentials (cached, renewed early), token relay of the inbound bearer token, or an API key from the secret store. |
| `OutboundHeaders` | Headers forwarded to a dependency from the current request: correlation id, language and the catalog headers in the client's allow-list. |
| `ReactiveClientNameObservationConvention` | Uses the logical client name as `client.name` in metrics and spans of a WebClient. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-http-client-spring-boot-autoconfigure verify`.

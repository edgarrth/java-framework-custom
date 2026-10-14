<!-- generated -->
# axiz-web-spring-boot-autoconfigure

Correlation, header catalog validation, pagination, API versioning, CORS and OpenAPI metadata (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-web-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizWebAutoConfiguration` | Web conventions for servlet and reactive applications: request context, header catalog, paging, CORS, API versioning and JSON customization (Boot's mapper is customized, never replaced). |
| `AxizWebEnvironmentPostProcessor` | Safe web defaults with the lowest precedence: virtual threads, graceful shutdown, no stack traces or exception messages in error responses, Boot's own problem details off (the errors starter owns them) and OpenAPI metadata from the application name. |
| `AxizWebFluxConfigurer` | WebFlux wiring: paging parameters, CORS and native API versioning. |
| `AxizWebMvcConfigurer` | Spring MVC wiring: paging parameters, CORS and native API versioning. |
| `AxizWebProperties` | Web conventions (`axiz.web.*`). |
| `MdcKeys` | MDC keys written by the web layer; the logging starter emits them as JSON fields. |
| `PageRequestArgumentResolver` | Spring MVC resolver for `PageRequest` controller parameters. |
| `PageRequestParser` | Parses `page`, `size` and `sort` query parameters. |
| `Paging` | Paging rules of a `PageRequest` controller parameter. |
| `ReactivePageRequestArgumentResolver` | WebFlux resolver for `PageRequest` controller parameters. |
| `RequestContextAccessor` | Bridges `RequestContext` with Micrometer context-propagation, so the metadata follows Reactor operators, executors and virtual threads. |
| `RequestMetadataFilter` | Servlet filter that opens the request context: correlation and request ids (reused only when safe), catalog header validation, MDC entries and the `X-Correlation-Id` response header. |
| `RequestMetadataResolver` | Builds `RequestMetadata` from an inbound request and validates the header catalog. |
| `RequestMetadataWebFilter` | Reactive counterpart of `RequestMetadataFilter`. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-web-spring-boot-autoconfigure verify`.

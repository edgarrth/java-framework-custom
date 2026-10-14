<!-- generated -->
# axiz-core-errors

Error model: types, categories with HTTP mapping, AxizException, RFC 9457 problem, externalized catalog.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-errors</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AccessException` | Authentication or authorization failure. |
| `AxizException` | Base exception of the framework. |
| `BusinessException` | A business rule rejected the operation. |
| `CoreErrors` | Error definitions owned by the framework itself (prefix `AXZ`). |
| `ErrorCatalog` | Externalized error catalog. |
| `ErrorCategory` | Error categories with their HTTP status. |
| `ErrorCode` | Error code with the form `PREFIX-CAPABILITY-NNNN`, for example `AXZ-HTTP-0001`. |
| `ErrorDefinition` | Catalog entry describing an error. |
| `ErrorMessageResolver` | Resolves localized messages for error detail; implemented by the i18n capability. |
| `ErrorType` | Nature of an error, independent of its HTTP representation. |
| `ExceptionMapper` | Translates third-party exceptions (persistence, serialization, ...) into axiz exceptions. |
| `ExternalErrorDetail` | What a dependency answered when it failed. |
| `ExternalServiceException` | A dependency failed or rejected the call. |
| `FieldViolation` | A validation failure on a single field. |
| `NotFoundException` | The requested resource does not exist. |
| `Problem` | RFC 9457 problem details plus axiz extension members (`code`, `correlationId`, `timestamp`, `violations`, `external`). |
| `ProblemFactory` | Builds RFC 9457 problems from exceptions. |
| `ProblemJson` | Serializes problems to `application/problem+json`. |
| `TechnicalException` | Unexpected failure inside the service. |
| `ValidationException` | The input is invalid; carries one violation per field. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-errors verify`.

<!-- generated -->
# axiz-core-errors

Modelo de errores: tipos, categorías con su mapeo HTTP, AxizException, problema RFC 9457 y catálogo externalizado.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-errors</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AccessException` | Fallo de autenticación o de autorización. |
| `AxizException` | Excepción base del framework. |
| `BusinessException` | Una regla de negocio rechazó la operación. |
| `CoreErrors` | Definiciones de error propias del framework (prefijo `AXZ`). |
| `ErrorCatalog` | Catálogo de errores externalizado. |
| `ErrorCategory` | Categorías de error con su estado HTTP. |
| `ErrorCode` | Código de error con la forma `PREFIX-CAPABILITY-NNNN`, por ejemplo `AXZ-HTTP-0001`. |
| `ErrorDefinition` | Entrada del catálogo que describe un error. |
| `ErrorMessageResolver` | Resuelve mensajes localizados para el detalle del error; lo implementa la capacidad i18n. |
| `ErrorType` | Naturaleza de un error, independiente de su representación HTTP. |
| `ExceptionMapper` | Traduce excepciones de terceros (persistencia, serialización, etc.) a excepciones de axiz. |
| `ExternalErrorDetail` | Lo que respondió una dependencia al fallar. |
| `ExternalServiceException` | Una dependencia falló o rechazó la llamada. |
| `FieldViolation` | Un fallo de validación en un solo campo. |
| `NotFoundException` | El recurso solicitado no existe. |
| `Problem` | Detalles de problema RFC 9457 más los miembros de extensión de axiz (`code`, `correlationId`, `timestamp`, `violations`, `external`). |
| `ProblemFactory` | Construye problemas RFC 9457 a partir de excepciones. |
| `ProblemJson` | Serializa problemas a `application/problem+json`. |
| `TechnicalException` | Fallo inesperado dentro del servicio. |
| `ValidationException` | La entrada es inválida; lleva una violación por campo. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-errors verify`.

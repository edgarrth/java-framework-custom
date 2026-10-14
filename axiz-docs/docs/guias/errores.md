# Errores

Todo error sale como **RFC 9457** (`application/problem+json`) con miembros de extensión de axiz,
y todo error de negocio tiene un **código de catálogo**.

```json
{
  "type": "https://errors.axiz.internal/PAYMENTS-BENEFICIARIESCRUD-0003",
  "title": "Beneficiary limit reached",
  "status": 422,
  "detail": "Llegaste al máximo de 50 beneficiarios.",
  "instance": "/v1/customers/C-1001/beneficiaries",
  "code": "PAYMENTS-BENEFICIARIESCRUD-0003",
  "correlationId": "01J9Z8...",
  "timestamp": "2026-10-11T14:03:22Z"
}
```

`type` se arma con `axiz.errors.type-base-uri` y el código (`about:blank` si no se define). Las
validaciones agregan `violations` (una por campo) y los fallos de dependencias `external`
(qué contestó el sistema externo, ya saneado).

## Definir errores

Códigos con la forma `PREFIJO-CAPACIDAD-NNNN`. Los del servicio viven en su clase de errores:

```java
public final class BeneficiaryErrors {
  public static final ErrorDefinition LIMIT_REACHED =
      ErrorDefinition.of("PAYMENTS-BENEFICIARIESCRUD-0003", ErrorCategory.UNPROCESSABLE, "Beneficiary limit reached");
  private BeneficiaryErrors() {}
}
```

`axiz generate error-code limit-reached --category unprocessable --title "..."` agrega la constante
con el siguiente número libre. La **categoría** decide el status HTTP y si el error es reintentable:

| Categoría | HTTP | Tipo |
|---|---|---|
| `BAD_REQUEST`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` | 400, 405, 415 | validación |
| `UNAUTHORIZED`, `FORBIDDEN`, `TOO_MANY_REQUESTS` | 401, 403, 429 | seguridad |
| `NOT_FOUND`, `CONFLICT`, `PRECONDITION_FAILED`, `UNPROCESSABLE` | 404, 409, 412, 422 | negocio |
| `INTERNAL`, `NOT_IMPLEMENTED`, `SERVICE_UNAVAILABLE` | 500, 501, 503 | técnico |
| `BAD_GATEWAY`, `GATEWAY_TIMEOUT` | 502, 504 | externo |

## Lanzar errores

```java
throw new BusinessException(BeneficiaryErrors.LIMIT_REACHED, "limit reached",
    Map.of("max", Integer.toString(maxPerCustomer)));
throw new NotFoundException("beneficiary", beneficiaryId);
throw new ValidationException(List.of(FieldViolation.of("amount", "positive", "must be positive")));
```

El segundo argumento es para el log (nunca se muestra al cliente); los parámetros alimentan el
mensaje localizado. Las excepciones de terceros (persistencia, serialización, clientes) se traducen
con `ExceptionMapper`s: nunca sale un stack trace ni un mensaje interno.

## Mensajes y catálogo externo

El `detail` se resuelve en el idioma de `Accept-Language` (por defecto `es-PE`) desde
`messages*.properties` con la llave `error.<código>` y parámetros `{nombre}`:

```properties
error.PAYMENTS-BENEFICIARIESCRUD-0003=Llegaste al máximo de {max} beneficiarios.
```

El catálogo también se puede publicar como ConfigMap `<servicio>-errors` (valores `errors` del
chart), para cambiar títulos o categorías por ambiente sin recompilar.

## Errores de dependencias

Los clientes HTTP y SOAP convierten las respuestas de error en `ExternalServiceException` con
`ExternalErrorDetail` (sistema, status, código y mensaje del proveedor). El adaptador decide qué
significa para el dominio: en el demo, `CoreBankingAdapter` convierte `CB-51` en
"fondos insuficientes" (422) y `CB-62`/`CB-63` en "cuenta no operativa".

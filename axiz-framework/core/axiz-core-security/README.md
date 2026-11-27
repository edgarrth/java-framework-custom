<!-- generated -->
# axiz-core-security

AxizPrincipal, mapeo de claims, modelo de autoridades, claves de límite de tasa y firmas de peticiones.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-security</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AuthorityMapper` | Mapea scopes y roles a nombres de autoridad (`SCOPE_x`, `ROLE_X`). |
| `AxizPrincipal` | Llamador autenticado, construido desde los claims del token de acceso. |
| `ClaimMapping` | Indica qué claims contienen cada atributo del principal. |
| `ClaimsMapper` | Mapea los claims validados del token a un `AxizPrincipal` según un `ClaimMapping`. |
| `InMemoryRateLimiter` | Limitador de tasa de ventana fija en memoria: para uso local, pruebas y servicios de una sola réplica. |
| `IssuerPolicy` | Lista de emisores confiables permitidos. |
| `RateLimitKeys` | Construye las claves del límite de tasa desde el principal autenticado. |
| `RateLimiter` | Puerto del limitador de tasa; la clave siempre proviene del token validado, nunca de una cabecera del cliente. |
| `RequestSignature` | Firmas HMAC-SHA256 (formato `AXIZ-HMAC-V2`) para callbacks y webhooks de socios: texto firmado sin ambigüedad, rotación de claves por identificador, ventana de tiempo y nonces de un solo uso. |
| `NonceStore`, `InMemoryNonceStore` | Protección contra repetición para `RequestSignature`; el almacén en memoria protege solo una instancia (en un clúster se usa `RedisNonceStore`). |
| `TrustedIssuer` | Un emisor que el servicio acepta. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-security verify`.

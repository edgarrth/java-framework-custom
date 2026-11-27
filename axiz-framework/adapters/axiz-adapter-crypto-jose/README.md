<!-- generated -->
# axiz-adapter-crypto-jose

Utilidades JWS y JWE.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-crypto-jose</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `Jose` | Utilidades JWS y JWE con una lista fija de algoritmos permitidos: PS256, RS256 y ES256 para firmas; RSA-OAEP-256 con A256GCM para cifrado. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-crypto-jose verify`.

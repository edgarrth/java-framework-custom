<!-- generated -->
# axiz-adapter-crypto-kms

Proveedor de claves de AWS KMS para cifrado de sobre con caché de claves de datos.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-crypto-kms</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `KmsAsyncDataKeyProvider` | Cliente KMS nativo con cancelación exacta del SDK, claves de datos AES-256 y política compartida de contexto de cifrado. |
| `KmsDataKeyProvider` | Claves de datos desde AWS KMS. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-crypto-kms verify`.

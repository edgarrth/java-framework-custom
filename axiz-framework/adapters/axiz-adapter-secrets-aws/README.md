<!-- generated -->
# axiz-adapter-secrets-aws

Resolvedores de secretos para AWS Secrets Manager y Parameter Store.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-secrets-aws</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ParameterStoreResolver` | Resuelve parámetros (incluido SecureString) desde AWS Systems Manager Parameter Store bajo una ruta como `/axiz/prod/payments/`. |
| `SecretsManagerResolver` | Resuelve secretos desde AWS Secrets Manager. |
| `SecretsManagerAsyncResolver` | Consulta nativa con `SecretsManagerAsyncClient`, incluida la selección JSON `name#field`. |
| `ParameterStoreAsyncResolver` | Consulta nativa de SecureString con `SsmAsyncClient`, que convierte `#` en `/`. |

Los resolvedores nativos devuelven valores `CompletionStage` del JDK y preservan la cancelación de la llamada
al SDK. Toman prestados sus clientes del SDK; el cierre de los clientes corresponde a la aplicación. Solo la
respuesta de recurso no encontrado del SDK permite pasar a otra fuente. Los errores de transporte, de
autorización y de JSON mal formado se propagan; los diagnósticos de JSON inválido no incluyen datos secretos.
Los campos JSON estructurados se conservan como JSON válido.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-secrets-aws verify`.

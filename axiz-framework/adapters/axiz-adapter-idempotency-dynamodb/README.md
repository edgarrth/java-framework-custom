<!-- generated -->
# axiz-adapter-idempotency-dynamodb

Almacén de idempotencia en DynamoDB con TTL.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-idempotency-dynamodb</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `DynamoDbIdempotencyStore` | Almacén de idempotencia en DynamoDB: escritura condicional para iniciar (clave nueva o registro vencido), lecturas fuertemente consistentes y atributo TTL `ttl` para la retención. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-idempotency-dynamodb verify`.

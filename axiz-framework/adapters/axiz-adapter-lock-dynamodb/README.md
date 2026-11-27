<!-- generated -->
# axiz-adapter-lock-dynamodb

Lock distribuido con escrituras condicionales de DynamoDB.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-lock-dynamodb</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `DynamoDbDistributedLock` | Lock con escrituras condicionales de DynamoDB: se adquiere cuando el elemento no existe o su lease venció; se renueva y libera solo con el token del propietario. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-lock-dynamodb verify`.

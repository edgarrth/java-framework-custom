<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-sequence-dynamodb

Almacén de secuencias sobre un contador atómico de DynamoDB.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-sequence-dynamodb</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `DynamoDbSequenceStore` | Contador atómico sobre DynamoDB (`ADD value :inc` con `UPDATED_NEW`). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-sequence-dynamodb verify`.

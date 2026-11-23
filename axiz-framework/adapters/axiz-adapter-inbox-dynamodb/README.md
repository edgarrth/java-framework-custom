<!-- generated -->
# axiz-adapter-inbox-dynamodb

Almacén de inbox en DynamoDB con TTL.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-inbox-dynamodb</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `DynamoDbAsyncInboxStore` | Escrituras condicionales asíncronas nativas con cancelación exacta del SDK; comparte las reglas de formato con el almacén síncrono. |
| `DynamoDbInboxStore` | Inbox en DynamoDB. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-inbox-dynamodb verify`.

<!-- generated -->
# axiz-adapter-inbox-r2dbc

Inbox (consumidor idempotente) en Aurora PostgreSQL sobre R2DBC.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-inbox-r2dbc</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `R2dbcInboxStore` | Inbox en Aurora PostgreSQL sobre R2DBC, con la misma tabla que el almacén JDBC (`axiz/sql/inbox-postgresql.sql`). |
| `ReactiveInboxStore` | Contrato nativo de Reactor que conserva el contexto de suscripción y de transacción. |
| `R2dbcReactiveInboxStore` | Sentencias diferidas con exclusión por propietario, purgas de tamaño acotado e I/O cancelable. |

Los flujos de aplicación usan `ReactiveInboxStore`. `R2dbcInboxStore` comparte esa implementación y
espera con un plazo solo en el límite de los workers dedicados de sondeo y purga. Ninguna sentencia
síncrona corre en un event loop. Ambas variantes usan el mismo esquema del inbox.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-inbox-r2dbc verify`.

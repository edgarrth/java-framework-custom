<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-dynamodb-spring-boot-autoconfigure

DynamoDB Enhanced Client, nombres de tabla por ambiente y repositorios genéricos (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-dynamodb-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizDynamoDbAutoConfiguration` | Un cliente DynamoDB por aplicación (observado, cadena de credenciales por defecto, región y endpoint de LocalStack opcionales desde `axiz.aws.*`), el cliente enhanced y la fábrica de repositorios. |
| `DynamoRepositories` | Crea repositorios sobre el único cliente enhanced, aplicando el prefijo de nombre de tabla. |
| `ReactiveDynamoRepositories` | Crea repositorios nativos y fachadas Reactor diferidas para aplicaciones WebFlux. |
| `ReactiveDynamoRepository` | API Mono con lecturas vacías, consultas de una página, cancelación nativa y errores del SDK sin cambios. |

Las aplicaciones servlet reciben clientes y repositorios síncronos. Las aplicaciones reactivas reciben
solo `DynamoDbAsyncClient` y `ReactiveDynamoRepositories`; el framework no crea un respaldo síncrono.
Un cliente asíncrono provisto por la aplicación evita los recursos de credenciales por defecto. Una
fábrica reactiva personalizada hace que el framework no cree ninguno de sus recursos propios. El cliente
propio inicializa credenciales, TLS y soporte DNS al arranque con `axiz.aws.*`; los métodos del
repositorio envían la I/O al suscribirse y conservan la cancelación a lo largo de las etapas de mapeo del
Enhanced Client.

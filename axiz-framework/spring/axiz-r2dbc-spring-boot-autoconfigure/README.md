<!-- generated -->
# axiz-r2dbc-spring-boot-autoconfigure

Aurora PostgreSQL para servicios reactivos sobre R2DBC: tokens IAM, valores por defecto del pool, Flyway sobre JDBC, ReactiveSql transaccional, auditoría, columnas cifradas y de dinero, endpoint de lectura (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-r2dbc-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AuditableRow` | Base de las filas de Spring Data R2DBC: id UUIDv7 ordenado por tiempo asignado al crear, bloqueo optimista y columnas de auditoría llenadas con el llamador de la petición (`id`, `version`, `created_at`, `created_by`, `updated_at`, `updated_by`). |
| `AxizR2dbcAuditingAutoConfiguration` | Auditoría de `AuditableRow` (y de cualquier entidad con campos `@CreatedBy`/`@CreatedDate`): el llamador de la petición y el reloj de la aplicación, en UTC. |
| `AxizR2dbcAutoConfiguration` | Persistencia relacional reactiva sobre Aurora PostgreSQL: un `ReactiveSql` que se une a la transacción reactiva del llamador, los convertidores de columnas axiz (`EncryptedText`, `Money`) para Spring Data R2DBC y un endpoint de lectura opcional. |
| `AxizR2dbcConverters` | Convertidores de columnas de tipos axiz para Spring Data R2DBC: `EncryptedText` se guarda como texto cifrado AES-256-GCM (con un contexto explícito entity.field o purpose:name, compartido con JPA solo cuando la aplicación declara ese propósito) y `Money` en su forma de texto (`PEN 150.00`). |
| `AxizR2dbcEnvironmentPostProcessor` | Valores por defecto de R2DBC (precedencia más baja) adecuados para Aurora: las conexiones se validan contra el servidor al tomarlas y se renuevan cada 10 minutos, de modo que un failover o un token IAM vencido nunca dejan conexiones muertas en el pool; la obtención de una conexión está acotada para que un pool agotado falle rápido en lugar de encolar peticiones indefinidamente. |
| `AxizR2dbcFlywayPasswordAutoConfiguration` | Con autenticación IAM, Flyway se conecta por JDBC con un token propio. |
| `AxizR2dbcProperties` | Aurora PostgreSQL sobre R2DBC (`axiz.r2dbc.*`). |
| `DatabaseClientReactiveSql` | `ReactiveSql` sobre el `DatabaseClient` de Spring: las sentencias se unen a la transacción reactiva del llamador (`@Transactional` o `TransactionalOperator`) y las observa la instrumentación de R2DBC. |
| `FlywayPasswordCustomizer` | Entrega a Flyway una conexión JDBC autenticada con un token IAM (la migración se ejecuta una vez al arranque, dentro de la vigencia del token). |
| `ReaderDatabase` | El endpoint de lectura del clúster Aurora (`axiz.r2dbc.reader.url`): un pool separado para consultas que toleran el retraso de las réplicas. |
| `RequestReactiveAuditor` | Auditor de `createdBy`/`updatedBy` para código reactivo: el usuario final, o la aplicación llamadora en peticiones máquina a máquina, leído de los metadatos de la petición que los starters web y de seguridad colocan en el contexto de Reactor (o del estado ligado al hilo de un consumidor); en otro caso, `unattributed`. |

Tipos relacionados: `AxizR2dbcIamAutoConfiguration` están en `axiz-r2dbc-aws-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-r2dbc-spring-boot-autoconfigure verify`.

## Adaptador IAM opcional

Los servicios que habilitan `axiz.r2dbc.iam.enabled` declaran `axiz-adapter-database-auth-aws` con scope
`runtime` o aportan su propio `DatabasePasswordProvider` para el pool principal. La capacidad R2DBC
generada incluye el adaptador AWS. El starter genérico y esta autoconfiguración no lo imponen a los
consumidores mínimos.
Una configuración IAM habilitada sin su adaptador ni un proveedor personalizado falla con un mensaje
accionable. Los pools de lectura con IAM usan proveedores de tokens separados por host y requieren el
adaptador. La autenticación ordinaria por contraseña, incluidos los pools de lectura con contraseña,
funciona sin él.

Los pools de escritura y de lectura validan sus opciones de conexión finales. El de lectura también se
comprueba después de sus decoradores. PostgreSQL usa `sslMode=verify-full` (o `ssl=true` nativo sin un
modo que lo sobrescriba). Este validador rechaza los callbacks opacos de contexto TLS, engine, parámetros
o verificador de hostname, porque su comportamiento posterior no puede deducirse del modo. Se configura la
opción nativa de CA para cada destino en lugar de instalar un callback que reemplace la validación del par.

Los procesos en segundo plano proporcionan una identidad de carga de trabajo explícita mediante su
proveedor de auditor; una identidad ausente o vacía se marca `unattributed` y nunca se atribuye en silencio a
un sistema confiable.

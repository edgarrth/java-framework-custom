<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-jpa-spring-boot-autoconfigure

Convenciones JPA neutrales respecto del proveedor. Los consumidores seleccionan `axiz-jpa-spring-boot-starter`;
`axiz-bom` gestiona su versión.

## Responsabilidades

- `AxizJpaAutoConfiguration`: entity listener, auditor de peticiones y reloj propios del contexto;
  valida los contextos de cifrado declarados explícitamente.
- `RequestAuditorProvider`: sujeto autenticado o ID de cliente; vacío cuando no existe; `AuditListener` marca la fila como `unattributed`.
- `AxizJpaEnvironmentPostProcessor`: sin open-session-in-view, UTC, batching, validación del esquema,
  ubicaciones de Flyway y valores por defecto acotados del pool Hikari. TLS de PostgreSQL usa `verify-full`.
- La creación y actualización del esquema se limitan a los entornos explícitos `local`, `test` o `ci`.
  Las aplicaciones desplegadas aplican migraciones de Flyway revisadas y validan el esquema.

## Diagnósticos sensibles

La salida SQL por stdout y los loggers SQL/JDBC de Hibernate (incluidos los valores de bind/extract, los
batches y los errores de restricciones) están en OFF por defecto. Fuera de `local`, `test` y `ci`,
habilitarlos hace fallar el arranque: el detalle de los errores de base de datos puede contener los valores
rechazados completos. Se usan códigos de problema saneados y logs de operación de Axiz. Las
configuraciones de logging personalizadas y las propiedades programáticas de SessionFactory son
responsabilidad de la aplicación; con ellas se ejecuta el canario de datos sensibles generado.

## Proveedores opcionales

El failover de AWS, la autenticación IAM y la identidad del workload pertenecen al módulo separado
[`axiz-jpa-aws-spring-boot-autoconfigure`](axiz-jpa-aws-spring-boot-autoconfigure.md).
Este módulo neutral no incorpora el AWS SDK ni el wrapper JDBC.

## Pruebas

Desde `axiz-framework` se ejecuta `./mvnw -pl spring/axiz-jpa-spring-boot-autoconfigure -am test`.
Los servicios JPA generados también verifican un fallo real de restricción de PostgreSQL con
`./mvnw -Dtest=SensitiveDataCanaryTest test` (requiere Docker).

Los procesos en segundo plano proporcionan una identidad de carga de trabajo explícita mediante su
proveedor de auditor; una identidad ausente o vacía se marca `unattributed` y nunca se atribuye en silencio a
un sistema confiable.

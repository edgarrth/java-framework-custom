<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-schema

Recursos SQL canónicos de las tablas de Axiz, bajo `axiz/sql/`. Este JAR contiene solo recursos: sin drivers, runtime ni ejecutor de migraciones al arrancar. Los adaptadores JDBC y la generación de proyectos consumen los mismos bytes.

La ejecución de las migraciones Flyway iniciales corresponde a la aplicación. Se seleccionan los fragmentos de las capacidades habilitadas, se revisan y se ejecutan con una cuenta con privilegios DDL. Las cuentas de runtime solo necesitan los privilegios de su operación. Los recursos están fuera de la ruta por defecto `db/migration` de Flyway y nunca se ejecutan de forma automática.

Los fragmentos SQL seleccionados se conservan en el historial versionado de migraciones de la aplicación. Flyway gestiona el orden y los checksums de las migraciones; para DDL se usa una identidad de despliegue separada y las identidades de runtime se limitan a sus operaciones de datos. Antes de aplicar un cambio de esquema se revisan los bloqueos, la concurrencia y los requisitos de conversión de datos.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-schema</artifactId>
</dependency>
```

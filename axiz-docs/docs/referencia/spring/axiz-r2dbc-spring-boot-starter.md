<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-r2dbc-spring-boot-starter

Aurora PostgreSQL para servicios reactivos sobre R2DBC.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-r2dbc-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Imágenes nativas

El starter incluye metadatos de native-image que inicializan el listener de observación del proxy R2DBC al
arranque de la aplicación. Por eso su logger de Micrometer usa el backend de logging de la aplicación en
ejecución. La configuración se limita a ese listener; las observaciones SQL siguen habilitadas.

Los consumidores con IAM agregan `axiz-adapter-database-auth-aws` con scope runtime. La capacidad R2DBC
lo selecciona para los servicios generados; los consumidores con contraseña simple pueden usar este
starter sin el RDS del AWS SDK.

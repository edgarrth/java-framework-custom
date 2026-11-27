<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-inbox-jdbc-spring-boot-autoconfigure

Integración de inbox con JDBC.

Este módulo es dueño de la API `pe.axiz.spring.inbox.jdbc`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-inbox-jdbc-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizInboxJdbcAutoConfiguration`
- `AxizInboxJdbcProperties`

## Configuración

Prefijos de propiedades propios: `axiz.inbox.*`.

## Operación

El esquema de la capacidad se aprovisiona con migraciones y se configura un pool de conexiones
acotado con TLS verificado. La composición transaccional requiere la misma identidad efectiva del
recurso, no solo dos clientes que apunten a la misma base de datos. Para flujos reactivos se
selecciona el proveedor R2DBC nativo; JDBC se ejecuta en la ruta imperativa.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-inbox-jdbc-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.

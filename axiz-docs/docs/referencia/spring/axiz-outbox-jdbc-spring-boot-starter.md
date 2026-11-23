<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-outbox-jdbc-spring-boot-starter

Integración del outbox JDBC con el relay compartido.

Este módulo es dueño de la API `pe.axiz.spring.outbox.jdbc.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-outbox-jdbc-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-outbox-jdbc-spring-boot-autoconfigure`](axiz-outbox-jdbc-spring-boot-autoconfigure.md).

## Operación

El esquema de la capacidad se aprovisiona con migraciones y se configura un pool de conexiones
acotado con TLS verificado. La composición transaccional requiere la misma identidad efectiva del
recurso, no solo dos clientes que apunten a la misma base de datos. Para flujos reactivos se
selecciona el proveedor R2DBC nativo; JDBC se ejecuta en la ruta imperativa.

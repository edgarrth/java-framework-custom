# Límites JDBC

Core conserva los puertos y la orquestación sin dependencias de `java.sql` o `javax.sql`.
`axiz-kit-jdbc` contiene `ConnectionProvider`, `JdbcTransactions` y `SqlStates`. Las
implementaciones JDBC de outbox, inbox, saga y secuencias pertenecen a sus adaptadores.
Los servicios declaran esos adaptadores en runtime y administran sus migraciones Flyway.

Las transacciones core usan `pe.axiz.core.commons.tx.TransactionRunner`. La adquisición y
finalización del outbox incluyen el propietario del lease. El preflight del esquema es
diagnóstico y de solo lectura; informa las tablas y columnas que faltan sin ejecutar DDL.

`SchemaInitializer` se reserva para inicialización explícita en desarrollo y pruebas. Los
proveedores JDBC sustituyen los identificadores canónicos por los configurados, incluidos
tablas auxiliares e índices; los nombres se validan antes de construir SQL. Esto no migra
datos ni renombra una instalación existente. En producción, Flyway u otra herramienta del
servicio debe aplicar las migraciones antes de iniciar tráfico.

El outbox durable necesita también la tabla `axiz_audit_chain`: su cabecera se confirma en
la misma transacción que los eventos. Un arranque saludable sin ejecutar comandos no prueba
que una migración incluya esa tabla. La demo de órdenes lo verifica registrando una orden
real y conserva una migración adicional para instalaciones que ya aplicaron `V1`.

<!-- generated -->
# axiz-saga-spring-boot-starter

Orquestador de sagas con estado persistido en PostgreSQL y recuperación de sagas detenidas.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-saga-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Protección técnica del payload

`axiz.saga.context-protection=envelope` activa el cifrado autenticado del payload;
`none` es el valor por defecto. El modo envelope requiere el `EnvelopeEncryptor` síncrono o nativo
correspondiente y hace fallar el arranque cuando falta. Los registros protegidos no se pueden
leer en modo `none`; la migración de datos y las claves de lectura retenidas se planifican antes
de cambiar el modo. Se recomienda revisar la [protección de almacenes técnicos](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).

JDBC es una integración separada: se instala `axiz-saga-jdbc-spring-boot-starter`.
La base neutral requiere un `SagaStateStore`; no instala un proveedor de base de datos.

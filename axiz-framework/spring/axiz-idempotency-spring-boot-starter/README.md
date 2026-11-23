<!-- generated -->
# axiz-idempotency-spring-boot-starter

Endpoints @Idempotent con Idempotency-Key y respuestas guardadas en Redis o DynamoDB.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-idempotency-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Protección técnica del payload

`axiz.idempotency.response-protection=envelope` activa el cifrado autenticado del payload;
`none` es el valor por defecto. El modo envelope requiere el `EnvelopeEncryptor` síncrono o nativo
correspondiente y hace fallar el arranque cuando falta. Los registros protegidos no se pueden
leer en modo `none`; la migración de datos y las claves de lectura retenidas se planifican antes
de cambiar el modo. Se recomienda revisar la [protección de almacenes técnicos](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).

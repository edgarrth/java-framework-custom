<!-- generated -->
# axiz-idempotency-spring-boot-autoconfigure

Endpoints @Idempotent con Idempotency-Key y respuestas guardadas en Redis o DynamoDB (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-idempotency-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizIdempotencyAutoConfiguration` | Operaciones `@Idempotent` con la cabecera `Idempotency-Key`: respuestas guardadas en ElastiCache (Redis) o DynamoDB y repetidas; 400, 409 y 422 ante un uso indebido. |
| `AxizIdempotencyProperties` | Idempotencia de API (`axiz.idempotency.*`) para operaciones anotadas con `@Idempotent`. |
| `IdempotencyFilter` | Almacena en buffer el cuerpo de las peticiones no seguras que llevan `Idempotency-Key` (hasta `maxBodySize`) para calcular su huella (método, ruta, query string tal como se envió y cuerpo), y guarda la respuesta cuando el handler termina. |
| `IdempotencyMethodInterceptor` | Aplica `@Idempotent` alrededor del método handler, **dentro** de la seguridad de métodos: se ordena después de los interceptores `@PreAuthorize`, `@Secured` y JSR-250 de Spring Security, de modo que una repetición solo se devuelve cuando el llamador sigue autorizado para esta petición, con los argumentos resueltos (variables de ruta, cuerpo) que necesitan las políticas. |
| `IdempotencyReplayHandler` | Escribe una respuesta guardada para un `IdempotentReplay` (pilas servlet y reactiva): estado guardado, cabeceras repetidas y los bytes originales, más `Idempotent-Replayed: true`. |
| `IdempotencyWebFilter` | Equivalente reactivo de `IdempotencyFilter`: para peticiones no seguras calcula la huella del cuerpo en buffer (método, ruta, query string tal como se envió y cuerpo) y la entrega al advice mediante el contexto de Reactor; el advice decide después de la autorización del método. |
| `IdempotentReplay` | Señal de control de flujo: la petición repite una ya completada y se debe devolver su respuesta guardada. |

Tipos relacionados: `AxizIdempotencyDynamoDbAutoConfiguration` están en `axiz-idempotency-dynamodb-spring-boot-autoconfigure`; `AxizIdempotencyRedisAutoConfiguration` están en `axiz-idempotency-redis-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-idempotency-spring-boot-autoconfigure verify`.

## Protección técnica del payload

`axiz.idempotency.response-protection=envelope` activa el cifrado autenticado del payload;
`none` es el valor por defecto. El modo envelope requiere el `EnvelopeEncryptor` síncrono o nativo
correspondiente y hace fallar el arranque cuando falta. Los registros protegidos no se pueden
leer en modo `none`; la migración de datos y las claves de lectura retenidas se planifican antes
de cambiar el modo. Se recomienda revisar la [protección de almacenes técnicos](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).

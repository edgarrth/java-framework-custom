<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-idempotency

Un resolvedor autorizado que confirma que no hubo efecto libera la clave y responde 409
`AXZ-IDEM-2409` (`RESOLVED_WITHOUT_EFFECT`). El llamador puede entonces repetir la petición con la
misma Idempotency-Key. `begin` nunca ejecuta ese reintento de forma automática. Un resultado incierto
sigue siendo `AXZ-IDEM-1409` (`OUTCOME_UNKNOWN`): se consulta el estado de la operación y se concilia
antes de decidir si otro intento es seguro.

Idempotencia de API: clave y huella, respuestas guardadas y detección de conflictos.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-idempotency</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `IdempotencyErrors` | Errores de la capacidad de idempotencia de API. |
| `IdempotencyRecord` | Estado guardado de una clave de idempotencia. |
| `IdempotencyService` | Idempotencia de API: la primera petición con una clave se ejecuta, una petición idéntica repetida recibe la respuesta guardada, una concurrente recibe 409 y la misma clave con otra petición recibe 422. |
| `IdempotencyStore` | Almacenamiento de los registros de idempotencia (Redis o DynamoDB en producción). |
| `Idempotent` | Marca un endpoint no idempotente (POST/PATCH) como que requiere una `Idempotency-Key`. |
| `InMemoryIdempotencyStore` | Almacén en memoria para pruebas y ejecuciones locales de una sola instancia. |
| `StoredResponse` | Respuesta guardada para reproducirla. |
| `StoredResponseCodec` | Serializa registros para almacenes clave-valor (Redis, atributo de texto de DynamoDB). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-idempotency verify`.

## Resultados inciertos y retención

Un lease es una reserva de ejecución vigente, no un permiso para que otro llamador repita una
operación incierta. `leaseUntil` y `retainUntil` son independientes. Al vencer el lease, un registro
`IN_PROGRESS` retenido equivale a `UNKNOWN`; no permite tomar el control. Una caída, un 5xx, un
timeout de integración o una cancelación mantienen la clave reservada y responden 409
`OUTCOME_UNKNOWN` hasta que un resolvedor explícito pueda establecer el resultado. Esa respuesta
nunca se evita usando otra clave de idempotencia para la misma operación de negocio; se consulta el
estado de la operación o su procedimiento de conciliación.

`IdempotencyResolver` (síncrono) y `AsyncIdempotencyResolver` (nativo) son puertos de extensión
opcionales; no se supone ninguna conciliación por defecto. Las resoluciones usan un CAS versionado:
entre resoluciones concurrentes hay un solo ganador. Las respuestas completadas son inmutables y se
reproducen. Solo un resultado sin efecto certificado de forma explícita
(`RejectedBeforeEffect`/`Resolution.withoutEffect()`) puede liberar una clave.

Workers virtuales propios renuevan los leases cada tercio del TTL configurado. Una renovación fallida
vuelve incierto el resultado. Construir `IdempotencyLease` directamente requiere un intervalo de
renovación de al menos 1 ms y un timeout de renovación positivo; los intervalos menores a un
milisegundo se rechazan en lugar de redondearse. Spring valida
`retention >= client-retry-window + in-progress-ttl`; los valores por defecto son 24 h, 23 h y 30 s.
Esta garantía está acotada por la retención: una pausa o caída del proceso que impida renovar más
allá de la retención restante puede vencer el registro. La ventana de reintento se fija según los
clientes reales; una retención vencida no ofrece deduplicación indefinida.

Los adaptadores CAS comparten `AsyncIdempotencyCas` como utilidad de proveedor; no forma parte de la
interfaz del almacén que ven las peticiones. Redis y DynamoDB usan su reloj inyectado para completar,
renovar y marcar como desconocido, y vuelven a comprobar la retención tras una contención. `false`
significa que la mutación no se confirmó, incluido el agotamiento de la contención acotada; nunca
autoriza una reproducción ni afirma la pérdida de propiedad sin releer el registro. El almacén en
memoria por defecto acepta una marca de tiempo confiable explícita y ejecuta su compare-and-set de
forma local.

El TTL de ejecución debe ser de al menos 3 milisegundos: la renovación ocurre cada tercio del lease
y su intervalo mínimo es de 1 milisegundo. Los constructores síncrono y asíncrono rechazan TTL
menores antes de crear cualquier reserva. Es un mínimo representable, no una recomendación de
producción; los leases y plazos de renovación se eligen por encima de las latencias observadas del
almacén y de la red.

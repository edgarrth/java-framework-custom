# Idempotencia

Los clientes reintentan; las operaciones que mueven dinero o crean recursos no pueden repetirse.
axiz ofrece idempotencia en tres niveles.

## API: `@Idempotent`

```java
@Idempotent
@PostMapping("/v1/customers/{customerId}/beneficiaries")
public ResponseEntity<Beneficiary> createBeneficiary(...) { ... }
```

El endpoint exige el header `Idempotency-Key` (el contrato lo declara) y:

| Situación | Respuesta |
|---|---|
| primera request con la llave | se ejecuta y se guarda la respuesta |
| misma llave, misma request (método, ruta, query string tal como se envió y cuerpo) | se devuelve la respuesta guardada, si el llamante sigue autorizado |
| misma llave mientras la primera sigue en curso | `409` |
| misma llave con otra request (otro cuerpo u otra query) | `422` |
| cuerpo mayor a `max-body-size` | `413` |
| el handler lanza una excepción o responde `5xx` | el resultado queda `UNKNOWN`; el reintento recibe `409` hasta reconciliar el efecto |
| el handler responde `4xx` (rechazo de negocio o validación) | se guarda y se repite: la misma request recibe la misma decisión y la operación nunca corre dos veces |
| respuesta mayor a `max-response-size` | se devuelve completa (sin acumularse en memoria); el replay trae status y headers, sin cuerpo, con `Idempotent-Body-Omitted: true` |

Para operaciones financieras conviene una respuesta pequeña y estable (id y `Location` del recurso
creado) en lugar de cuerpos grandes: el replay sin cuerpo deja al cliente consultar el resultado.

```yaml
axiz:
  idempotency:
    store: redis              # redis | dynamodb | memory (solo local, test y ci: falla en otros)
    in-progress-ttl: 30s
    retention: 24h
    max-body-size: 1048576    # request
    max-response-size: 1048576
```

### Alcance de la llave

Una llave es única por **operación** (clase y método del handler) y por **llamante**: emisor del
token, tenant, aplicación cliente y usuario final. Dos llamantes que usan la misma
`Idempotency-Key` ejecutan cada uno su operación y nunca reciben la respuesta del otro, aunque
vengan de emisores o tenants distintos. La query string es parte de la huella tal como se envió:
`?amount=10` y `?amount=99` con la misma llave son un `422`, y también `?sort=a&sort=b` frente a
`?sort=b&sort=a` (el orden puede tener significado); un cliente que reintenta reenvía la misma
petición.

El llamante se codifica parte por parte (marcador de presencia, texto y separador) y ninguna parte
puede contener caracteres de control (U+0000 a U+001F, U+007F): así ningún valor puede imitar un
separador y dos llamantes distintos nunca comparten alcance. El mapeo de tokens de axiz rechaza
con `401` un token cuyo emisor, cliente, sujeto o tenant trae esos caracteres, e
`IdempotencyService.scope` los rechaza también para integraciones que arman el alcance por su
cuenta, antes de iniciar la operación. Los llamantes válidos conservan el mismo alcance que en
versiones anteriores, así que las llaves en curso siguen deduplicando después de actualizar.

### Dueño de la llave

Cada ejecución recibe un token de dueño. Guardar la respuesta o liberar la llave solo funciona
mientras esa ejecución la tiene. Una escritura tardía no puede sobrescribir una reconciliación
ni una nueva ejecución autorizada. El vencimiento de la concesión no autoriza por sí solo
a repetir un efecto de negocio incierto. Redis lo resuelve con scripts Lua que comprueban estado y dueño en el mismo paso atómico;
DynamoDB, con escrituras condicionadas por `status` y `owner`.

Se elige un `in-progress-ttl` mayor que la duración máxima de la operación (timeouts de las llamadas que
hace incluidos). Axiz renueva la concesión mientras trabaja; si vence, conserva la incertidumbre
y exige reconciliación antes de volver a ejecutar.

### Autorización antes del replay

La decisión de idempotencia se toma **dentro de la seguridad por método**: un advisor envuelve los
handlers `@Idempotent` de los controladores y está ordenado después de `@PreAuthorize`,
`@Secured` y JSR-250. Cada request, también un reintento con la misma llave, pasa primero la
autorización vigente con sus argumentos resueltos (path variables, cuerpo); si el permiso o la
titularidad cambiaron, el reintento se rechaza sin devolver la respuesta guardada. La respuesta
guardada se escribe desde un manejador de excepciones interno (`IdempotentReplay`), no desde un
filtro previo.

Eso cubre las reglas declaradas en el controlador. Si la autorización por recurso vive en un
command CQRS (políticas de acceso, ver [Seguridad](seguridad.md#autorizacion-por-recurso)), la
idempotencia también debe vivir ahí (`IdempotentCommand`): el replay de un controlador que solo
envía el command no vuelve a pasar por el pipeline.

### Lo que la idempotencia HTTP no cubre

- **Caída entre el commit y el guardado de la respuesta.** El efecto quedó hecho pero la llave no
  tiene respuesta. La concesión vencida mantiene el resultado incierto y el reintento recibe
  `409`. Aporta un `IdempotencyResolver` que consulte el identificador único de la operación
  en la base de negocio y devuelva su respuesta confirmada, o confirme que no hubo efecto.
  Sin esa evidencia no se libera la llave. La reconciliación sin efecto devuelve un conflicto
  explícito y permite repetir la misma solicitud después; no ejecuta negocio dentro del resolver.
- **Streaming y SSE.** Las respuestas en streaming no son idempotentes por replay: no marques
  `@Idempotent` en esos endpoints.
- **Cancelación.** Si el cliente corta la conexión mientras el handler corre, la llave queda en curso
  o pasa a `UNKNOWN`; el reintento recibe `409` hasta reconciliar el resultado.

`IdempotencyService.fail` marca incertidumbre; no libera la llave.
`releaseWithoutEffect` solo corresponde cuando se comprobó que no hubo efecto. La retención
es un límite de deduplicación, por lo que la unicidad de una operación financiera también debe
residir en su base de negocio. Una llave expirada no sustituye ese control persistente.

## Comandos: `IdempotentCommand`

En CQRS, un command que implementa `IdempotentCommand` (con su `idempotencyKey()`) guarda su primer
resultado y lo devuelve para la misma llave y el mismo llamante; la misma llave con otro command es
un `422`. El behavior de idempotencia corre después del de autorización, así que un replay solo
llega a quien todavía puede ejecutar el command.

## Hacia abajo: llaves derivadas

Un proceso que llama a varios sistemas deriva una llave determinista por paso
(`IdempotencyKeys`), así un reintento, un evento reentregado o una saga reanudada nunca repiten un
efecto. En el demo, `PaymentInstruction.keyFor("debit")` produce `pp-<orden>-debit` y viaja como
`Idempotency-Key` (o `X-Request-Ref` hacia el core) en cada llamada de la saga.

## Consumo de eventos

Lo resuelve el [inbox](eventos.md#inbox-procesar-una-sola-vez): cada mensaje se procesa una vez por
consumidor.

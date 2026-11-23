# Datos sensibles y auditoría

## Clasificar

Un dato sensible se marca una vez, en el modelo, y axiz lo enmascara en cada salida según su nivel:

```java
public record BeneficiaryView(
    String id,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.PARTIAL) String cci,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.NAME) String holderName,
    String alias) {}
```

| Nivel | Ejemplos |
|---|---|
| `LOW` | datos de contacto parciales |
| `MEDIUM` | cuentas, documentos, nombres |
| `HIGH` | PAN, cuentas y documentos: máscara específica que nunca muestra el valor completo |
| `CRITICAL` | CVV, PIN, contraseñas, secretos: siempre enmascarado completo |

Máscaras: `EMAIL`, `PHONE`, `DOCUMENT`, `CARD`, `PARTIAL`, `NAME` (inicial de cada palabra:
`Ana Torres -> A** T*****`), `FULL` y `NONE`.

## Dónde se enmascara

| Salida | Propiedad | Por defecto |
|---|---|---|
| Respuestas de la API | `axiz.sensitive.response` | `MEDIUM` (se enmascara desde ese nivel) |
| Logs (incluido el log HTTP) | `axiz.sensitive.log` | `LOW` (todo lo marcado) |
| Eventos de auditoría | `axiz.sensitive.audit` | `MEDIUM` |

Las respuestas de la API se enmascaran en el conversor JSON de las respuestas del servidor (servlet)
o en su encoder (reactivo). El `JsonMapper` de la aplicación **no** enmascara: con él se serializan
los payloads de eventos, el contexto de las sagas, los valores en caché, los resultados idempotentes
y los cuerpos de las llamadas salientes, que deben conservar el valor real (una cuenta enmascarada en
un evento llegaría al consumidor, y de ahí al core, como `*********5678`). Si una clase necesita el
JSON enmascarado fuera de una respuesta, registra `SensitiveJacksonModule` en su propio mapper
(`mapper.rebuild().addModule(new SensitiveJacksonModule(policy)).build()`).

Desde `axiz.sensitive.full-mask-level` (por defecto `CRITICAL`) el valor siempre se enmascara
completo. Para datos sin
anotaciones (mapas, cuerpos HTTP crudos, headers) `axiz.sensitive.keys` declara nombres de
propiedades o headers sensibles con su nivel y máscara; `JsonTextMasker` los enmascara dentro del
JSON. Lo lee como JSON estricto (RFC 8259) en una sola pasada, en tiempo lineal y con profundidad
acotada (64 niveles):

- Los nombres se comparan ya decodificados: `"pass\u0077ord"` es `password`.
- Un valor sensible de texto, número o booleano se enmascara con su máscara; si es un objeto o un
  arreglo se reemplaza entero por `"****"`, contenga lo que contenga.
- Los textos se decodifican antes de enmascarar y el resultado se vuelve a escribir como JSON
  válido, sin espacios insignificantes.
- Lo que no es **un** valor JSON completo (un cuerpo cortado por un límite, dos documentos
  seguidos, un error de sintaxis o un anidamiento mayor al límite) nunca se devuelve tal como
  llegó: `mask` responde `<not a complete JSON value: not logged>`. Un escaneo tolerante no puede
  prometer eso: un escape válido o un texto truncado dejarían pasar un secreto.

En el log HTTP el cuerpo se enmascara completo y recién después se trunca a
`axiz.logging.http.max-body-length`. Un cuerpo de más de `axiz.logging.http.max-masked-body-length`
caracteres (por defecto 65536) no se guarda para enmascararlo: se registra solo su tipo y tamaño,
igual que un cuerpo que no es JSON válido.

## Logs

- JSON estructurado con traza, span, correlación, tenant y headers de negocio del contexto.
- Log HTTP de entrada y salida con **lista blanca de headers** (nunca `Authorization` ni `Cookie`).
  Los cuerpos (`axiz.logging.http.bodies=true`, enmascarados y truncados) solo se permiten en
  `local`, `test` y `ci`: en cualquier otro ambiente el servicio no arranca, porque un cuerpo libre
  puede llevar datos de autenticación que ningún enmascarador garantiza detectar.
- `@LogOperation` registra entrada y salida de un método con duración, resultado y argumentos
  enmascarados cuando se solicitan; `logArguments=false` y `logResult=false` por defecto.
  `@NoLog` excluye un parámetro, un resultado o un método.
- Prohibido `System.out` y `printStackTrace` (ArchUnit y Checkstyle).

La [clasificación de claves](../referencia/clasificacion-sensible.md) se genera desde el código.
El reconocimiento separa camelCase, guiones y separadores y busca cada término sensible en
**cualquier posición** del nombre, con o sin dígitos finales: cubre `customerPassword`,
`billing_card_number`, `cvvCode`, `pinCode`, `passwordConfirmation` y `privateKeyPem`. Un término
seguido de una palabra que describe metadatos suyos (`tokenCount`, `pinAttempts`, `passwordPolicy`,
`tokenUrl`, `secretArn`) no se considera sensible; si coinciden varios términos decide el más
sensible. No identifica cualquier abreviatura del negocio: amplía `axiz.sensitive.keys` para esos
nombres. La regla ArchUnit de datos de tarjeta usa el mismo reconocimiento.

La detección por valor de PAN complementa las claves y no se puede desactivar en ambientes
desplegados. Reconoce 12 a 19 dígitos que cumplen Luhn, con dígitos Unicode (por ejemplo de ancho
completo) y grupos separados por hasta dos espacios, tabuladores, espacios no separables, guiones o
rayas, `.`, `/` o `_`. No identifica contraseñas arbitrarias.

Hibernate puede imprimir el valor rechazado por una restricción. El starter JPA desactiva la
impresión SQL directa y los loggers SQL/JDBC de Hibernate, incluidos errores, bindings y valores
extraídos. Fuera de `local`, `test` y `ci`, una configuración que los habilite impide arrancar.
Los fallos siguen disponibles mediante códigos, clases y métricas saneados de Axiz. No pases
excepciones completas, parámetros SQL ni secretos a loggers propios, APM o appenders externos.

Cada servicio generado incluye `SensitiveDataCanaryTest`: arranca su configuración real y
comprueba logs, auditoría, problemas y el conversor/encoder de respuestas con PAN, CVV y contraseña
sintéticos. Recorre éxito, fallo, timeout y evidencia de rollback. Los servicios JPA provocan
además una violación UNIQUE en PostgreSQL y revierten la transacción, comprobando que el mensaje
del driver no sale al log. Los workers sin HTTP prueban el canal de respuesta del enmascarador;
su serializador de eventos conserva los datos. Este canario se amplía con los DTO, integraciones y
appenders propios, sin datos reales. Se ejecuta `./mvnw -Dtest=SensitiveDataCanaryTest test` con Docker.

## Auditoría

```java
@Auditable(action = "account.debit", resource = "account", captureArguments = false)
public Movement execute(Input input) { ... }
```

Cada ejecución produce un `AuditEvent` (quién, qué acción, sobre qué recurso, cuándo, con qué
resultado y, si se captura, argumentos y resultado saneados por sensibilidad). `policy` decide si se
registra siempre, solo en éxito o solo en fallo. También se puede auditar cada request HTTP
(`axiz.audit.http`).

Cada evento toma una instantánea profunda, acotada y saneada de sus detalles. Un cambio posterior
en el objeto de origen no cambia la evidencia. El modo de entrega se elige explícitamente fuera
de desarrollo: `ASYNC` usa una cola volátil; `DURABLE` incorpora el éxito a la transacción del
negocio mediante outbox; `SYNC` espera acuse, sin atomicidad con el negocio. Un fallback al log
no demuestra entrega ni persistencia durable. Los proveedores se seleccionan por separado:

| Capacidad | Destino | Formato |
|---|---|---|
| `audit` | logger `axiz.audit` (JSON) | línea JSON |
| `audit-kafka` | tópico de MSK (`axiz.audit.kafka.topic`) | CloudEvent binario (`ce_*`) |
| `audit-sqs` | cola SQS (`axiz.audit.sqs.queue-url`) | CloudEvent estructurado |

El tipo CloudEvents es `pe.axiz.audit.event.v1`, así un `event-processor` puede consumirlos como
cualquier otro evento (en el demo, `notifications-processor` los archiva en S3).

Ver [Seguridad](seguridad.md#auditoria-durable) para fallos, sellado, publicación estricta,
checkpoints y límites de las garantías.

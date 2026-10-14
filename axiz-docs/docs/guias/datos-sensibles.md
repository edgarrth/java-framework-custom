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
| `HIGH` | datos financieros completos |
| `CRITICAL` | PAN completo, CVV, PIN, contraseñas, secretos: siempre enmascarado completo |

Máscaras: `EMAIL`, `PHONE`, `DOCUMENT`, `CARD`, `PARTIAL`, `NAME` (inicial de cada palabra:
`Ana Torres → A** T*****`), `FULL` y `NONE`.

## Dónde se enmascara

| Salida | Propiedad | Por defecto |
|---|---|---|
| Respuestas de la API | `axiz.sensitive.response` | `MEDIUM` (se enmascara desde ese nivel) |
| Logs (incluido el log HTTP) | `axiz.sensitive.log` | `LOW` (todo lo marcado) |
| Eventos de auditoría | `axiz.sensitive.audit` | `MEDIUM` |

Desde `axiz.sensitive.full-mask-level` (por defecto `CRITICAL`) el valor siempre se enmascara
completo. Para datos sin
anotaciones (mapas, cuerpos HTTP crudos, headers) `axiz.sensitive.keys` declara nombres de
propiedades o headers sensibles con su nivel y máscara; `JsonTextMasker` los enmascara dentro del
JSON sin parsearlo.

## Logs

- JSON estructurado con traza, span, correlación, tenant y headers de negocio del contexto.
- Log HTTP de entrada y salida con **lista blanca de headers** (nunca `Authorization` ni `Cookie`) y
  cuerpos enmascarados y truncados.
- `@LogOperation` registra entrada y salida de un método con duración, resultado y argumentos
  enmascarados; `@NoLog` excluye un parámetro, un resultado o un método.
- Prohibido `System.out` y `printStackTrace` (ArchUnit y Checkstyle).

## Auditoría

```java
@Auditable(action = "account.debit", resource = "account", captureArguments = false)
public Movement execute(Input input) { ... }
```

Cada ejecución produce un `AuditEvent` (quién, qué acción, sobre qué recurso, cuándo, con qué
resultado y, si se captura, argumentos y resultado saneados por sensibilidad). `policy` decide si se
registra siempre, solo en éxito o solo en fallo. También se puede auditar cada request HTTP
(`axiz.audit.http`).

Los eventos se publican **asíncronamente** (cola acotada drenada por un virtual thread; si se llena,
van al log en lugar de perderse) a uno o varios destinos:

| Capacidad | Destino | Formato |
|---|---|---|
| `audit` | logger `axiz.audit` (JSON) | línea JSON |
| `audit-kafka` | tópico de MSK (`axiz.audit.kafka.topic`) | CloudEvent binario (`ce_*`) |
| `audit-sqs` | cola SQS (`axiz.audit.sqs.queue-url`) | CloudEvent estructurado |

El tipo CloudEvents es `pe.axiz.audit.event.v1`, así un `event-processor` puede consumirlos como
cualquier otro evento (en el demo, `notifications-processor` los archiva en S3).

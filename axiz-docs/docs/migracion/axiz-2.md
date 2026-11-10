# Migrar a Axiz 2.0

La migración cambia coordenadas y paquetes de tecnología, separa estructuras de extensiones y
traslada handlers de EDA a messaging. El dominio puede seguir usando contratos de Axiz sin
importar frameworks terceros. El formato de los eventos y las identidades persistentes se conservan.

## Procedimiento

1. Usa una candidata verificada con coordenadas finales `2.0.0`, en un repositorio Maven aislado.
   La identidad de la candidata es su manifiesto firmado, no un sufijo RC.
2. Ejecuta `axiz upgrade --to 2.0.0`. El manifiesto pasa a schema 2; OpenRewrite migra paquetes,
   dependencias, propiedades y `ArchitectureTest`. El comando termina ejecutando `doctor`.
3. Un arquetipo anterior `integration` requiere una decisión explícita: `--archetype core` para
   un sistema interno o `--archetype gateway` para un tercero.
4. Revisa el diff y ejecuta `./mvnw -Pquality verify`, contratos HTTP/eventos e integraciones.
   En reactive, las pruebas deben habilitar BlockHound y comprobar cancelación y reconexión.
5. Promueve los artefactos exactos de la candidata únicamente después de completar la aceptación.

CQRS se declara con `structure: hexagonal` y `extensions: [cqrs]`. Las APIs de persistencia
pasan a `pe.axiz.kit.*`; solo se importan en repositorios o adaptadores de persistencia.
Los adaptadores `axiz-adapter-*` se declaran en runtime.

## Renombrar un servicio

`--rename <capacidad>` cambia el nombre de despliegue y conserva el paquete Java y las identidades
estables. Fija explícitamente `axiz.messaging.source`, `axiz.messaging.consumer.name`,
`axiz.messaging.kafka.group-id` y `axiz.config.parameter-store.application`; no cambia tablas,
colas, topics, secretos ni client IDs OAuth. `.axiz/identities.yaml` permite detectar alteraciones.

El chart incorpora alias DNS y audiencia durante la convivencia con llamadores 1.x. Su retirada
requiere coordinación. El renombre automático actual trabaja con YAML; un proyecto configurado
con `application.properties` requiere adaptar esa configuración antes del renombre.

## Estado de aceptación

1.0.0 con 2.0.0 y rechaza rupturas no previstas. Los reportes funcionales, de mutación y de
integración deben revisarse junto con las capacidades pendientes; no se publica 2.0 por el hecho
de haber actualizado el POM.

## Núcleo de lenguaje y auditoría explícita

`Checks`, `Strings`, `Dates` y `Result` (incluidas `Ok`/`Err`) pasan de
`pe.axiz.core.commons` a `pe.axiz.core.lang`, en `axiz-core-lang`. Declara esa dependencia si
usas los helpers en el dominio. OpenRewrite migra los tipos; commons conserva las utilidades
operacionales y depende del nuevo módulo. `LockTiming` se retira a favor de `LeaseTiming`.

Fuera de local/test, declara `axiz.audit.mode: DURABLE|ASYNC|SYNC` para los publicadores de Axiz.
DURABLE necesita un outbox en la misma transacción del negocio, con destino y relay configurados.
La receta conserva los selectores heredados ambiguos para revisión manual; perfiles sin selectores
no impiden migrar la base cuando no hay importaciones o activación que alteren la precedencia.

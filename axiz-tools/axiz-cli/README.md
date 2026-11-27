# axiz-cli

El comando `axiz`: crea servicios a partir de los arquetipos, añade capacidades, genera artefactos
(casos de uso, handlers, entidades, clientes HTTP, migraciones), mantiene sincronizados el código y los
contratos OpenAPI/AsyncAPI, actualiza los servicios entre versiones del framework y comprueba un
proyecto (`axiz doctor`).

`doctor` informa las credenciales literales en YAML y `.properties`, incluidos los valores multilínea
y las API keys con forma de UUID. Se usan propiedades de referencia explícitas como `client-secret-ref`
o marcadores de variables de entorno. Los nombres de cabeceras y las propiedades de modo de
autenticación son metadatos de configuración; un valor en minúsculas o que termina en `-ref` no
convierte una credencial en una referencia.

Incluye el motor de generación del framework (`pe.axiz:axiz-codegen`), por lo que funciona sin conexión
y produce exactamente lo mismo que el initializr y CI. Las fuentes generadas salen con el formato que
espera `spotless:check`: el jar incluye google-java-format y exporta en su manifiesto (`Add-Exports`) los
paquetes de `jdk.compiler` que necesita; el binario nativo, donde el formateador no puede ejecutarse,
ejecuta en su lugar `./mvnw -q spotless:apply` del proyecto.

```bash
mvn -pl axiz-cli -am package             # genera target/axiz-cli-<version>-jar-with-dependencies.jar
java -jar axiz-cli/target/axiz-cli-*-jar-with-dependencies.jar catalog
mvn -pl axiz-cli -am -Pnative package    # binario nativo target/axiz (GraalVM 25)
```

Comandos y opciones: ver el README de `axiz-tools` y `axiz --help`.

## Generación de proyectos en línea

`axiz new ... --server https://initializr.example` usa `AXIZ_TOKEN` del entorno. La CLI no persiste
ese token ni sigue redirecciones HTTP. Los errores HTTP informan el estado sin imprimir el cuerpo de la
respuesta del servidor, que puede contener datos enviados.

Los archivos comprimidos descargados y expandidos se limitan a 50 MiB cada uno; un archivo puede
contener como máximo 5.000 entradas. La extracción rechaza rutas inseguras, destinos que son enlaces
simbólicos, rutas de salida duplicadas y conflictos entre archivos y directorios antes de escribir los
archivos del proyecto. `--force` permite reemplazar archivos regulares, pero no desactiva estas
comprobaciones. El destino debe ser un directorio bajo el control del usuario; se usa un directorio
privado si otro proceso pudiera reemplazar sus subdirectorios de forma concurrente.

## Agregadores Maven

Los comandos de proyecto se ejecutan con `--dir` apuntando al directorio que contiene
`.axiz/project.yaml` y el POM raíz. La CLI sigue los módulos Maven declarados y selecciona la
aplicación que contiene el `application.yaml`, `application.yml` o `application.properties` base. Con
varias aplicaciones, las rutas de contratos del manifiesto deben identificar exactamente una; una
selección ambigua falla antes de escribir archivos. Las rutas de los módulos no pueden salir del
proyecto ni seguir enlaces simbólicos.

La generación, la sincronización de contratos, las dependencias de capacidades y las migraciones
Flyway usan ese módulo de aplicación. Las instantáneas y el registro de identidad quedan junto al
manifiesto raíz. Las comprobaciones reactivas exigen BlockHound en las pruebas de la aplicación, sin
exigirlo en una librería Java hermana. Una actualización cambia todos los parents de Axiz declarados;
`--rename` cambia solo el artifactId de la aplicación y su identidad de despliegue. Conserva el
agregador, los artefactos hermanos, los paquetes Java y las identidades con estado. Los bindings de los
workflows generados de build y despliegue (módulo de aplicación, POM de origen y directorio del chart
propio) se actualizan con el renombre; los workflows personalizados quedan como un parche para
revisión.
Los editores pueden leer POM de reactor nombrados de forma explícita, pero el build estándar de
servicios requiere `<module>/pom.xml`; actualizar ese workflow con otro nombre de POM también produce
una propuesta y conserva el proyecto original.

## Contratos AsyncAPI

`axiz new ... --asyncapi events.yaml` genera records Java de payload, esqueletos tipados de handlers de
entrada, metadatos separados de binding de Spring AOT y las suscripciones `receive` exactas. Las
operaciones AsyncAPI 3 deben referenciar canales y mensajes locales; `message.name` es el tipo
CloudEvents versionado (`pe.axiz.orders.created.v1`). Se rechazan las referencias externas o cíclicas
y los traits sin aplanar. Los records de payload contienen tipos del JDK y se ubican en `domain.event`
en los servicios hexagonales o en `event` en las demás estructuras.

`axiz contract sync --type asyncapi` añade payloads y propone ediciones como `.axiz-new`, conservando
el código existente. Los campos opcionales pueden extender el tipo publicado; los cambios incompatibles
requieren un tipo `.vN` nuevo en paralelo. `--allow-breaking` no puede anular esta regla. Se usa
`contract lint --type asyncapi` para las comprobaciones estructurales locales y
`contract diff --type asyncapi old.yaml new.yaml` para la compatibilidad. El lint corporativo de
Spectral se ejecuta en CI. Con solo un contrato AsyncAPI, `sync` y `lint` lo seleccionan de forma
automática.

`axiz generate event order-created-v1` lee el payload AsyncAPI del proyecto; nunca reemplaza un record
existente sin `--force`. `doctor` compara los tipos recibidos con las suscripciones de la configuración
base y de las sobrescrituras explícitas de perfiles (YAML o properties), y rechaza una instantánea
desactualizada. El esqueleto del handler rechaza los mensajes hasta que se implementa su acción de
negocio, por lo que se implementa antes de consumir mensajes reales. La verificación de suscripciones
en runtime rechaza los handlers ausentes. La comparación de esquemas no puede demostrar que un campo
sin cambios conserva su significado de negocio; eso se revisa en cada cambio de contrato.

`axiz generate event-handler <name-vN>` (o `handler`) usa el tipo recibido del contrato y su payload.
Los servicios reactivos obtienen `ReactiveTypedEventHandler` con un método `Publisher<Void>`; los
servicios servlet obtienen `TypedEventHandler`. Rechaza un tipo que solo se produce y conserva las
implementaciones existentes. Los metadatos de binding nativo de Spring quedan en `config`, fuera de la
capa de payloads.

Los planes de prueba reactivos se activan con `@ExtendWith(BlockHoundExtension.class)`. El listener de
JUnit del framework instala la detección antes de que arranquen los schedulers; no se requiere el flag
de autodetección de extensiones de JUnit. `doctor` reconoce la anotación real y excluye comentarios y
literales al inspeccionarla.

## Comprobación previa del outbox

`axiz doctor --outbox-jdbc-url jdbc:postgresql://HOST:5432/DATABASE` activa un reporte de solo lectura
sobre PostgreSQL usando `AXIZ_DOCTOR_DB_USER` y `AXIZ_DOCTOR_DB_PASSWORD` del entorno. Se usa una
cuenta de base de datos de solo lectura; las credenciales no se incluyen en la URL ni en el historial
del shell. `--outbox-table` sobrescribe el identificador de tabla validado. Las comprobaciones normales
de `doctor` y `upgrade` no se conectan a una base de datos de forma automática.

La consulta canónica del framework lista cada evento `FAILED` con su agregado, destino, error, intentos
y si existen sucesores publicados o pendientes. Nunca cambia estados. Cada fila se revisa antes de la
ventana de mantenimiento: `retry` conserva el evento; `skip` requiere una decisión explícita y auditada
y es terminal. Reintentar no puede reconstruir el orden de sucesores que ya se publicaron.

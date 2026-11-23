<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-codegen

Motor de generación compartido por axiz-initializr y axiz-cli: modelo y lint de OpenAPI, reglas de compatibilidad, plantillas y escritura segura.

Los proyectos CQRS generan una política de acceso por cada operación protegida del contrato. Las
políticas exigen un llamador autenticado y todos los scopes declarados; las operaciones públicas
declaran `OpenAccess` de forma explícita. Las pruebas generadas verifican el rechazo de llamadas
anónimas y con scopes parciales. Las comprobaciones de propiedad de objetos corresponden a las
políticas de la aplicación consumidora. La sincronización del contrato propone políticas y pruebas de
autorización actualizadas como archivos `.axiz-new`, y conserva las reglas escritas a mano para su
revisión.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-codegen</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ArtifactGenerator` | `axiz generate <type> <name>`: código coherente con el estilo, el stack y la persistencia del proyecto. |
| `ContractSync` | `axiz contract sync`: después de editar `src/main/openapi/openapi.yaml`, genera los esqueletos de las operaciones nuevas, propone controladores actualizados (archivos `.axiz-new` junto a los existentes, sin sobrescribir nunca el código), informa las operaciones eliminadas y comprueba la compatibilidad hacia atrás contra la instantánea del último contrato sincronizado. |
| `GenerationException` | La petición no puede generarse; lleva todos los problemas encontrados, no solo el primero. |
| `HttpClientGenerator` | `axiz generate http-client <name> --contract <openapi>`: un cliente declarativo (interfaz `@HttpExchange`, un método por operación) de una API de terceros o de un servicio posterior, sus modelos como records y su registro en el grupo de servicios HTTP `<name>`, que la capacidad http-client configura desde `axiz.http-client.clients.<name>.*` (URL base, timeouts, reintentos, circuit breaker, client credentials de OAuth2, observaciones). |
| `Names` | Convenciones de nombres del código generado. |
| `ProjectGenerator` | Genera un proyecto completo (servicio, librería o starter) a partir de una petición: validación, contexto y plantillas. |
| `RequestValidator` | Reglas de compatibilidad de arquetipo, estructura, extensiones, stack, persistencia y capacidades. |
| `Archetype` | Rol de un servicio en el ecosistema y los valores por defecto que preselecciona. |
| `Capability` | Capacidad que el initializr y `axiz add` pueden añadir a un servicio. |
| `Catalog` | Arquetipos y capacidades que incluye el framework (`axiz/archetypes/catalog.yaml` y `axiz/archetypes/capabilities.yaml`). |
| `Structure` | Estructura de la aplicación: hexagonal, layered, EDA, BFF o batch; los proyectos de plataforma usan `NONE`. |
| `Extension` | Extensión de arquitectura opcional, como CQRS, validada contra el arquetipo y la estructura. |
| `ProjectRequest` | Qué se genera. |
| `Stack` | Stack web de un servicio. |
| `ApiOperation` | Una operación del contrato, con su política `Access` explícita y sus scopes. |
| `ContractDiff` | Comprobación de compatibilidad entre dos versiones de un contrato desde el punto de vista de los consumidores existentes: las operaciones eliminadas, las nuevas entradas obligatorias, los campos de respuesta eliminados o con otro tipo y los enums reducidos rompen la compatibilidad; las operaciones nuevas, las entradas opcionales y los campos de respuesta nuevos no. |
| `LintFinding` | Violación de una regla del contrato. |
| `OpenApiDocument` | Lector mínimo y seguro de documentos OpenAPI 3.x (YAML o JSON): operaciones, tags, esquemas y ejemplos que necesitan los generadores. |
| `OpenApiLinter` | Reglas corporativas de contratos, las mismas del ruleset de Spectral en axiz-code-standards, que el initializr y la CLI aplican antes de generar código. |
| `SafeYaml` | Cargador YAML para contratos no confiables: constructor seguro (sin tipos arbitrarios), límites de alias y de tamaño, sin claves duplicadas y con las fechas como texto para no reescribir los ejemplos. |
| `JavaFormatter` | Formatea las fuentes Java generadas con google-java-format (y elimina los imports sin uso) cuando está en el classpath y puede ejecutarse (el initializr y el jar de la CLI lo incluyen con los exports de `jdk.compiler` que necesita), de modo que los proyectos generados pasan `spotless:check` desde el primer build. |
| `TemplateSet` | Conjunto de plantillas Mustache descrito por `axiz/templates/<set>/manifest.yaml`. |
| `YamlProperties` | Propiedades de Spring Boot en YAML: las claves planas (`a.b[0].c`) se anidan en mapas y listas y se escriben en estilo de bloque, para fusionar propiedades de capacidades sin claves raíz duplicadas. |
| `CapabilityAdder` | `axiz add <capability>`: añade el starter y sus adaptadores al pom, sus propiedades a `application.yaml` (y al perfil local), las claves del ConfigMap a los values de Helm de cada ambiente, sus secretos al ExternalSecret, las tablas que necesita como una nueva migración Flyway y la capacidad al manifiesto. |
| `Migrations` | Versiones Flyway de las migraciones nuevas: el siguiente entero después del archivo `V<n>__` más alto, para que las migraciones generadas continúen la numeración propia del proyecto (`V1__init`, `V2__...`). |
| `PomEditor` | Ediciones mínimas de un `pom.xml` que conservan el formato: añade dependencias antes del cierre `</dependencies>` del proyecto (nunca dentro de dependencyManagement ni de perfiles) y actualiza la versión del parent. |
| `ProjectManifest` | Manifiesto `.axiz/project.yaml` de esquema 2: rol, estructura, extensiones, stack, paquetes, capacidades y rutas de contratos que usan los editores de proyectos. |
| `MavenReactor` | Lee los POM declarados del reactor y sus parents de Axiz sin ejecutar Maven; rechaza módulos duplicados o cíclicos, rutas externas, enlaces simbólicos y entidades XML externas. |
| `ProjectLayout` | Selecciona un módulo de aplicación y resuelve sus destinos generados y los charts de Helm propios. El manifiesto, las instantáneas y los metadatos de identidad permanecen en la raíz del proyecto. |
| `GeneratedProject` | Archivos generados que se mantienen en memoria hasta escribirse. |
| `SafePaths` | Validación de las rutas relativas generadas y escritura segura bajo un directorio base: sin rutas absolutas, sin `..` y sin seguir enlaces simbólicos debajo de la base (un enlace plantado en el destino, como `src -> /etc`, redirigiría la escritura fuera de ella). |
| `SampleProjects` | Genera proyectos de ejemplo para que CI los compile como lo haría un equipo: `defaults` crea un proyecto por arquetipo con su estilo, stack y persistencia por defecto; `all` crea todas las combinaciones válidas. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl codegen/axiz-codegen verify`.

Los editores de proyectos comparten `ProjectLayout` cuando un servicio está debajo de un agregador
Maven. Una única configuración de aplicación identifica su módulo; con varios candidatos, las rutas de
contratos del manifiesto deben seleccionar exactamente uno. La ambigüedad falla antes de escribir. Los
charts propios pueden estar en la raíz o en el módulo de aplicación y conservan su directorio cuando
cambian los nombres de despliegue. Es un lector estructural, no un resolvedor del modelo efectivo de
Maven: las declaraciones de módulos deben tener rutas relativas literales.

## Evolución de AsyncAPI

`AsyncApiDocument` resuelve las operaciones locales de AsyncAPI 3 en los tipos versionados de
CloudEvents que el servicio publica y consume. Rechaza claves YAML duplicadas, referencias remotas o
cíclicas, traits sin aplanar y expansiones que superan su presupuesto de tamaño y profundidad.
`AsyncApiDiff` conserva las identidades publicadas y acepta la adición de campos opcionales, incluidos
objetos anidados e ítems de arreglos; otros cambios en el formato de transporte requieren un tipo
versionado paralelo. Las descripciones y los ejemplos editoriales pueden cambiar sin modificar el
esquema de transporte; los cambios de significado de negocio requieren revisión humana.

`EventPayloads` comparte el mapeo de tipos de records de aplicación y produce records de payload con
tipos del JDK, sin anotaciones de frameworks. La generación inicial usa las suscripciones de recepción
reales y omite el ejemplo de EDA no relacionado. `AsyncContractSync` conserva las implementaciones,
crea los records nuevos, escribe propuestas `.axiz-new` y avanza la instantánea de generación solo
después de las comprobaciones de compatibilidad y de destino. `AsyncApiSubscriptions` comprueba la
configuración base y las sobrescrituras explícitas de perfiles sin arrancar Spring ni resolver
secretos. La integración con la CLI y el control del build están documentados en `axiz-tools/axiz-cli`
y en `.github/docs/pipelines.md`.

`EventAdapters` genera esqueletos tipados de consumidores y configuraciones de binding de Spring AOT
por payload, fuera del paquete propio de payloads. El stack selecciona la base de handler imperativa o
reactiva; los receptores usan su identidad CloudEvents declarada y rechazan mensajes hasta que se
implementa el tratamiento de negocio. La sincronización crea los adaptadores que faltan y conserva las
implementaciones y la configuración de binding existentes. Los contratos de consumo requieren una
capacidad de mensajería declarada. La expansión de referencias tiene un presupuesto compartido en todo
el contrato, incluidas las operaciones repetidas.

## Acceso HTTP explícito

La generación de servicios y la sincronización del contrato exigen una política de acceso admitida
para cada operación. El `security` a nivel de operación sobrescribe el requisito raíz; una declaración
omitida hereda el de la raíz. El generador no infiere acceso público a partir de scopes ausentes.

| Política del contrato | Protección generada |
|---|---|
| Un requisito OAuth2/OpenID Connect con scopes | `@PreAuthorize` que exige todos los scopes declarados |
| Un requisito HTTP bearer u OAuth2/OpenID Connect con lista de scopes vacía | `@AuthenticatedAccess` |
| `security: []` explícito | `@PublicEndpoint` y la entrada correspondiente en `axiz.security.public-paths` |

Las declaraciones ausentes, los esquemas desconocidos, los requisitos alternativos o múltiples y los
esquemas no admitidos se rechazan antes de escribir un servicio. También se rechaza un patrón de ruta
pública que se superpone con una ruta solo autenticada: el comparador público del framework se basa
en la ruta. Se usan rutas distintas o guardas de método con scopes. La sincronización del contrato
conserva la configuración de la aplicación; la configuración de rutas públicas se revisa al fusionar
la propuesta de un controlador que pasa a ser público. El parseo de contratos externos de clientes
HTTP es independiente de estas reglas de generación de servicios.

## Recursos de auditoría generados

Los destinos de auditoría en AWS siguen siendo obligatorios en producción. La inicialización de
LocalStack crea una cola o un tópico SNS dedicado `<service>-audit`, y el perfil local apunta a él.
La `AuditTestConfiguration` generada aprovisiona recursos reales y registra la URL de cola o el ARN de
tópico devueltos mediante un inicializador de contexto antes de evaluar las condiciones de la
autoconfiguración. Los clientes síncronos temporales de preparación se cierran de inmediato; los
clientes de aplicación, los pools de finalización y las métricas los crean las autoconfiguraciones de
producción. La clase de integración cierra su contexto al terminar la clase, restaura las credenciales
de prueba del SDK y detiene su contenedor, y se ejecuta aislada cuando la ejecución paralela de JUnit
está activada. El fixture genérico de LocalStack del framework no aprovisiona en silencio ningún
recurso de la aplicación.

Los jobs regulares `samples` y nocturnos `samples-matrix` verifican seis consumidores adicionales:
servlet y reactivo, cada uno con outbox/SQS, outbox/SNS y `audit-sqs`. Su manifiesto está separado de
los nueve arquetipos por defecto para que la selección de módulos nativos conserve su alcance
explícito. El job de aceptación HTTP también ejercita peticiones con scopes, solo autenticadas y
públicas en ambos stacks.

## Recetas generadas ejecutables

Los servicios generados comparten una configuración abstracta de pruebas de aplicación. Los servicios
web arrancan en un puerto aleatorio; las pruebas HTTP con el JDK ejercitan la salud y la autorización
anónima a través del servidor real (y del event loop reactivo con BlockHound). Otras pruebas en
proceso invocan a los colaboradores de la aplicación con un llamador sintético autorizado y esperan
inicialmente el contrato explícito 501; esa expectativa se reemplaza al implementar la operación.
Otras pruebas de controladores ejercitan el estado exitoso y el mapeo JSON con dobles de prueba y
fixtures sintéticos tipados. Las pruebas de modelos hexagonales verifican colecciones anulables e
inmutables. La prueba de arranque invoca el método main real, salvo en los CronJobs, cuyo main termina
la JVM.

La aceptación batch carga dos veces un CSV sintético desde LocalStack S3 en PostgreSQL y comprueba la
normalización, el filtrado y la supresión de duplicados. El generador de ejemplos de CI añade una
entidad, un repositorio y una receta real de aceptación de persistencia a cada variante de
persistencia: crear, leer, actualizar y borrar, además de consultas por partición en DynamoDB y avance
de versión. Las claves efímeras de cursor existen solo en ese contexto de prueba; ningún secreto
compartido queda incrustado en las fuentes generadas.

Ambos workflows de ejemplos exigen `-Pquality`, igual que el pipeline de build de los servicios. La
sincronización del contrato crea el soporte de pruebas compartido que falta. Las clases de prueba y
los fixtures de respuesta nuevos o modificados siempre se convierten en propuestas `.axiz-new`,
incluidos los fixtures que el usuario eliminó a propósito. Se fusionan con las propuestas de sus
controladores antes de ejecutar las pruebas de las operaciones nuevas. Las comparaciones de respuestas
JSON son STRICT, incluidos los campos adicionales y el orden de los arreglos.

Los nombres de parámetros OpenAPI a nivel de ruta, de operación y de referencia local deben cumplir
`^[A-Za-z][A-Za-z0-9_.-]{0,63}$`; los literales de las anotaciones Java se codifican después de la
validación. El lint rechaza comillas, caracteres de control, secuencias de escape Unicode y nombres
fuera del límite antes de generar.

### Nombres de artefactos seguros

Los subpaquetes de artefactos y los tipos calificados de origen y destino de los mappers deben ser
nombres Java válidos. La sintaxis de paquete inválida se rechaza antes de renderizar o escribir
archivos. Los nombres simples de artefactos y tipos conservan su conversión documentada de kebab-case
a PascalCase. Los modelos de clientes HTTP rechazan valores de enum o propiedades que se normalizan al
mismo identificador Java en lugar de generar código que no compila.

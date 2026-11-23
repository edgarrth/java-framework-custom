<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-test-containers

Herramientas para contenedores de pruebas, independientes de Spring y del core de producción.
`TestContainerImages.postgres()`, `valkey()`, `kafka()`, `wiremock()`, `keycloak()` y `localstack()` devuelven contenedores sin
contactar Docker hasta `start()`. Importar este artefacto solamente con alcance `test`; el
starter de pruebas lo incorpora para sus configuraciones de PostgreSQL, Redis y Kafka.

```java
try (var database = TestContainerImages.postgres().withDatabaseName("example")) {
  database.start();
  // Usar database.getJdbcUrl(), getUsername() y getPassword().
}
```

Las recetas en `src/main/resources/pe/axiz/quality/containers/images` son la fuente única.
Fijan la imagen base por digest. PostgreSQL reemplaza `gosu` por `su-exec`; Valkey actualiza
los paquetes afectados y fija las versiones corregidas. Los paquetes y sus metadatos se
conservan para que los escáneres puedan inspeccionarlos. La imagen local lleva el SHA-256
completo de todos los archivos enumerados en `files.list` (o del Dockerfile para recetas de
un solo archivo), evitando reutilizar una imagen de otra revisión. Kafka utiliza la
distribución JVM 4.2.2 con Jackson Core/Databind, Snappy y LZ4 corregidos y descargados con
SHA-256 fijado. Su prueba publica y consume mensajes con ambos códecs. La distribución JVM
permite inspeccionar los JAR incluidos; un escaneo del sistema operativo de la variante nativa
no permite inventariar el código Java incorporado.

WireMock 3.13.2 y Keycloak 26.8.2 usan una base Temurin 25/Alpine actualizada y ejecutan
como UID 1000. WireMock usa la distribución oficial `wiremock-jetty12` y sus dependencias
en JAR separados con metadatos Maven, versiones y SHA-256 registrados en
`images/wiremock/libraries.lock.json`. La imagen final solo extrae la clase de arranque
del ejecutable original; no incluye su JAR autónomo, que oculta las dependencias reubicadas
a los escáneres. Jetty y sus módulos EE se alinean en 12.1.14. El motor Handlebars 4.5.2
corrige CVE-2026-55760; los helpers permanecen en 4.3.1 porque WireMock 3 requiere su
paquete Java original. Son artefactos distintos: el motor vulnerable no se conserva.
Una prueba comprueba el rechazo de rutas padre, directorios hermanos y enlaces simbólicos
fuera de las plantillas. Otra registra stubs con el cliente oficial y procesa SOAP con
plantillas en el contenedor real. La variante Jetty 12 tiene
[diferencias documentadas](https://wiremock.org/docs/jetty-12/) para mensajes de estado HTTP,
multipart y redirecciones; las extensiones deben ser compatibles con esa variante.

Keycloak incorpora Guava 33.7.2-jre, OpenTelemetry API 1.67.0 y MariaDB
3.5.11 verificados por SHA-256. Su índice de arranque Quarkus conserva los nombres originales:
enlaces simbólicos resuelven esas rutas hacia los JAR corregidos, conservando sus metadatos
Maven reales. No se conserva el contenido de las bibliotecas sustituidas. La prueba verifica
el hash resuelto, importa un realm, valida criptográficamente un JWT contra su JWKS y exige
rechazar credenciales incorrectas. Estas son imágenes derivadas mantenidas en este repositorio
para desarrollo y pruebas; `keycloak()` arranca con `start-dev`.

LocalStack Community 4.14.0 se reconstruye con Python 3.13 y Java 25 sobre Wolfi.
Los cambios Python tienen hashes en `requirements.lock`; el inventario Java está en
`libraries.lock.json`, con los SHA-256 de cada descarga comprobados durante el build.
Conserva DynamoDB, S3, SQS, SNS, KMS, Secrets Manager, Parameter Store, STS e IAM;
no es un emulador genérico de todos los servicios AWS. No necesita una cuenta AWS ni
licencia remota. El primer arranque puede tardar por las descargas; los siguientes
reutilizan la imagen identificada por el contenido de la receta.
Ejecuta como UID/GID 1000, con código y ejecutables no modificables por ese usuario.
Los datos y la caché se escriben en `/var/lib/localstack` y los temporales en `/tmp`.

Para LocalStack se usa `AxizContainers.localstack()` o `TestContainerImages.localstack()` y se
aplica `withServices(...)` al contenedor, de modo que las pruebas usen siempre la imagen corregida.
No se sustituye por un nombre de imagen fijo en cada consumidor.

Testcontainers mantiene activo Ryuk. `PatchedRyukImageSubstitutor` construye su
fuente oficial 0.14.0 con Go 1.27.4 y el cliente Docker mantenido; la preparación
evita inicializar recursivamente el recolector de recursos. El binario no usa UPX,
de modo que el escáner puede inventariar sus dependencias Go. La limpieza al salir
de la JVM se conserva y se verifica con contenedores reales.
Ryuk es un controlador de infraestructura de pruebas: necesita acceso al socket Docker
del trabajador y ejecuta explícitamente como UID 0 porque el grupo del socket varía por host.
Ese acceso ya permite administrar el daemon. Debe quedar en trabajadores locales/CI aislados,
sin compartir el socket con aplicaciones. La clasificación puntual DS-0002 de sus tres
recetas está documentada y vence en `.trivyignore.yaml`; ningún CVE queda excluido.

La receta `otel-lgtm` se comparte con Compose y los arquetipos. Construye Grafana,
Loki, Tempo, Prometheus y un colector OTLP mínimo con fuentes y dependencias fijadas;
no requiere Go en la máquina del consumidor. El frontend de Grafana corresponde al
mismo release del servidor. Mantiene métricas, logs, trazas y dashboards; no incluye
un backend de perfiles. Se ejecuta como UID 10001; solamente `/data` y `/tmp`
necesitan escritura. Los puertos internos de los backends escuchan en loopback.
`python3 .github/scripts/check_observability_image.py axiz-local/otel-lgtm:2026.11-r1
--report /tmp/axiz-observability.json` inicia un contenedor efímero, envía las tres señales
por OTLP, las consulta a través de Grafana y comprueba ejecución sin privilegios con el
sistema de archivos de solo lectura. CI ejecuta esa misma prueba.
Grafana permite lectura anónima para esta demo local; no desplegar esta configuración
de desarrollo como plataforma de observabilidad de producción.

Los entornos Compose de la demo y de los proyectos generados publican sus puertos únicamente
en `127.0.0.1`. Sus credenciales de desarrollo no autorizan exposición a otras máquinas.

Preparar un entorno Python con `python3 -m venv .venv` y
`.venv/bin/python -m pip install --require-hashes -r axiz-framework/.github/scripts/requirements.txt`;
activarlo con `source .venv/bin/activate`.
Desde la raíz del repositorio, ejecutar `python3 .github/scripts/sync_container_recipes.py`
para actualizar las copias de la demo y los generadores; `--check` detecta diferencias.
Para actualizar WireMock, resolver primero el SBOM CycloneDX del módulo con alcance de
pruebas y exportar su cierre transitivo (sustituir las rutas por las del inventario y repositorio Maven):

```bash
mvn -f axiz-framework/pom.xml -pl quality/axiz-test-containers \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeBom \
  -DincludeTestScope=true -DoutputFormat=json
python3 .github/scripts/update_wiremock_image.py \
  --sbom axiz-framework/quality/axiz-test-containers/target/bom.json \
  --repository "$HOME/.m2/repository"
python3 .github/scripts/sync_container_recipes.py
```

El exportador rechaza dependencias ausentes, versiones incompatibles, nombres ambiguos y
SNAPSHOT. La sincronización verifica que la receta corresponda al lock. Actualizar la versión
principal de WireMock requiere además verificar y fijar el hash del lanzador original.
El resultado de cada construcción debe pasar el escaneo M/H/C con una base vigente de
vulnerabilidades. Una receta corregida hoy no garantiza que futuras vulnerabilidades no la afecten.

CI conserva el escaneo bruto y el resultado VEX. La única clasificación de código ya
corregido está en `.github/security/tempo-fixed.openvex.json`: dos CVE de Tempo que el
escáner atribuye a una pseudo-versión anterior a v2, cuyo commit ya contiene ambos
parches oficiales. La declaración identifica CVE y PURL/version exactos, enlaces a
los commits y fecha límite de revisión. No autoriza aceptar una vulnerabilidad
existente ni omitir dependencias. El gate rechaza revisiones vencidas, supresiones
sin prueba de parche y cualquier otra vulnerabilidad MEDIUM/HIGH/CRITICAL/UNKNOWN,
incluso cuando todavía no haya una versión corregida.
Los módulos Go internos de los workspaces de Grafana/Loki no tienen tags independientes.
`sources.lock.json` registra su módulo, ruta, hash de `go.mod`, release y archivo fuente
verificado. El hash del inventario queda en una etiqueta de la imagen. El gate exige esa
coincidencia para reconocerlos y rechaza cualquier dependencia sin versión ajena a ese
inventario; conserva intacta la salida original del escáner.

Validación: `mvn -f axiz-framework/pom.xml -pl quality/axiz-test-containers -am -Pquality
-Daxiz.it.requireDocker=true verify`. Las pruebas de integración inicializan las bases,
comprueban el reemplazo efectivo del ejecutable vulnerable y ejecutan operaciones reales.

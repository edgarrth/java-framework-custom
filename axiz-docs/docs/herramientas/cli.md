# CLI

`axiz` es un binario nativo (GraalVM) para Linux, macOS y Windows, publicado en cada release de
`axiz-tools` junto con su checksum; también corre como `java -jar axiz-cli.jar`.

```text
axiz new <name> --archetype <a> [--structure] [--stack] [--persistence] [-c <capability>]... --openapi <file>
axiz new -i                                   interactivo
axiz add <capability>...                      starter, propiedades, llaves de ConfigMap, tablas, ejemplo
axiz generate <type> <name>                   usecase, command, query, handler, event, event-handler,
                                              entity, repository, controller, dto, mapper,
                                              http-client (--contract), migration, error-code, test
axiz contract sync [--allow-breaking]         operaciones nuevas, propuestas para las existentes, compatibilidad
axiz contract lint [file] | diff <old> <new>
axiz upgrade [--to <version>] [--dry-run]     parent + manifiesto + recetas de OpenRewrite
axiz doctor                                   herramientas, versiones, coherencia, contrato, secretos, anti-patrones
axiz local up | down [-v]                     docker compose del servicio
axiz catalog [archetypes|capabilities]
```

Todos los comandos aceptan `-h` y `-V`; los que trabajan sobre un proyecto aceptan `-d <dir>`.

## Código formateado

Lo que generan `new`, `add`, `generate` y `contract sync` queda con el formato que exige
`spotless:check`, sin necesitar `spotless:apply` adicional. Aprobar `quality` también requiere
implementar y probar el comportamiento del servicio y cumplir sus umbrales de cobertura. El jar
trae google-java-format y declara en su manifiesto (`Add-Exports`) el acceso a `jdk.compiler` que
necesita; basta con ejecutarlo en un JDK 25. El binario nativo no puede correr el formateador:
al terminar ejecuta `./mvnw -q spotless:apply` en el proyecto (o `mvn` si no hay wrapper) y, si no
encuentra Maven, avisa qué correr.

## Servicios dentro de un reactor Maven

Se usa `--dir` con la raíz que contiene `.axiz/project.yaml` y el POM agregador. Los comandos
siguen los módulos declarados y seleccionan la aplicación que contiene la configuración base
`application.yaml`, `application.yml` o `application.properties`. Si hay varias aplicaciones,
las rutas de contratos del manifiesto deben identificar exactamente una; una selección
ambigua se rechaza antes de escribir.

Fuentes, dependencias, configuración y migraciones se modifican en ese módulo. Los snapshots
y el registro de identidades permanecen junto al manifiesto raíz. `upgrade --rename` conserva
el artifactId del agregador y las bibliotecas hermanas; actualiza los parents Axiz declarados
y renombra el servicio preservando sus identidades persistentes. Doctor exige BlockHound en
las pruebas de la aplicación reactiva, sin exigirlo a una biblioteca Java pura del reactor.

La migración de workflows estándar fija el módulo, su POM y el directorio existente del chart
para los consumidores independientes de build y deploy. Los workflows personalizados y un POM
de servicio con nombre distinto de `pom.xml` producen una propuesta para revisión y conservan
el proyecto original. El lector estructural no resuelve el modelo efectivo de Maven ni módulos
cuyas rutas dependan de propiedades.

## Modo offline y online

Por defecto la CLI genera **offline** con las plantillas embebidas en el binario. Con
`--server https://initializr.axiz.internal` genera a través del initializr usando el token del
usuario en `AXIZ_TOKEN` (la CLI nunca lo guarda), útil para usar plantillas más nuevas que el
binario instalado. El token debe estar emitido para el initializr (audiencia `axiz-initializr`, o la
que defina `axiz.initializr.audience`); un token del mismo SSO para otra API recibe 401.

## `axiz doctor`

Revisa la instalación (Java 25 y herramientas en el PATH), el manifiesto `.axiz/project.yaml` y sus
capacidades, el contrato (existe, lint, diferencias con el código, propuestas de `contract sync`
sin resolver), que exista `ArchitectureTest`, valores con
forma de secreto en la configuración (`password`, `secret`, `token`, `api-key`... con valor
literal) y anti-patrones conocidos en el código. Termina con el total de errores y avisos y
devuelve código distinto de cero si hay errores, así que sirve en CI.

## Códigos de salida

| Código | Significado |
|---|---|
| 0 | éxito |
| 1 | problemas encontrados (lint, diff incompatible, doctor) |
| 2 | uso incorrecto (opciones inválidas) |

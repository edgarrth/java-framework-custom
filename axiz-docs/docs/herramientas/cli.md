# CLI

`axiz` es un binario nativo (GraalVM) para Linux, macOS y Windows, publicado en cada release de
`axiz-tools` junto con su checksum; también corre como `java -jar axiz-cli.jar`.

```text
axiz new <name> --archetype <a> [--framework] [--stack] [--persistence] [-c <capability>]... --openapi <file>
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

## Modo offline y online

Por defecto la CLI genera **offline** con las plantillas embebidas en el binario. Con
`--server https://initializr.axiz.internal` genera a través del initializr usando el token del
usuario en `AXIZ_TOKEN` (la CLI nunca lo guarda), útil para usar plantillas más nuevas que el
binario instalado. El token debe estar emitido para el initializr (audiencia `axiz-initializr`, o la
que defina `axiz.initializr.audience`); un token del mismo SSO para otra API recibe 401.

## `axiz doctor`

Revisa la instalación (Java 25 y herramientas en el PATH), el manifiesto `.axiz/project.yaml` y sus
capacidades, el contrato (existe, lint, diferencias con el código, propuestas de `contract sync`
forma de secreto en la configuración (`password`, `secret`, `token`, `api-key`... con valor
literal) y anti-patrones conocidos en el código. Termina con el total de errores y avisos y
devuelve código distinto de cero si hay errores, así que sirve en CI.

## Códigos de salida

| Código | Significado |
|---|---|
| 0 | éxito |
| 1 | problemas encontrados (lint, diff incompatible, doctor) |
| 2 | uso incorrecto (opciones inválidas) |

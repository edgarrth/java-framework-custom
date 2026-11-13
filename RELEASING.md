# Publicación del monorepo

Repositorio: `edgarrth/java-framework-custom`. Las fuentes, acciones reutilizables y
configuraciones se mantienen en este único historial Git.

## Componentes y versiones

| Directorio | Componente | Ejemplo de tag |
|---|---|---|
| `axiz-framework` | Framework Java | `axiz-framework-v2.0.0` |
| `axiz-tools` | Herramientas | `axiz-tools-v2.0.0` |
| `axiz-docs` | Documentación | `axiz-docs-v2.0.0` |
| `.github` | Workflows y acciones | `axiz-workflows-v2.0.0` |

Las versiones evolucionan de forma independiente. Los consumidores fijan una release
inmutable de workflows; no se mantienen tags móviles ni selectores Axiz 1.x.
La demo demuestra el consumo del framework y no es un producto publicable del SDK.

## Primera release

`release-please-config.json` y `.release-please-manifest.json` en la raíz gobiernan los
cuatro componentes. El manifiesto comienza vacío: no afirma que ya existan releases.
Cada componente usa `initial-version: 2.0.0`. Framework y herramientas crean un tag de build y
una release borrador que su deploy completa con los artefactos verificados. Workflows y
documentación publican su release al crearla; los consumidores requieren workflows ya inmutables.

El límite exclusivo del historial inicial es el commit real
`64b138e185332e4148dc1e2c96eab8cf32236604`. Incluye como cambios iniciales los commits
posteriores; no se recalcula con `HEAD`. Tras la primera release, Release Please utiliza
sus tags y manifiesto para determinar el historial de cada componente.

La App de GitHub configurada en `RELEASE_APP_ID` y `RELEASE_APP_PRIVATE_KEY` crea los PR
y releases desde `main`. Se requiere una release de workflows inmutable antes de ejecutar
los consumidores que la fijan. Crear estos archivos no crea tags ni publica artefactos.

## Build y deploy

El build compila, prueba y produce artefactos identificados por el commit de origen,
los parámetros y sus hashes. El deploy consume los artefactos verificados y su identidad;
no debe compilarlos otra vez. Ambos procesos son independientes.

Las credenciales de lectura, firma y publicación se asignan al job que las necesita.
La identidad esperada de una firma utiliza el repositorio y la ruta del workflow en
`.github/workflows` junto con el tag inmutable `axiz-workflows-vX.Y.Z`.

## Estado de implementación

La migración y sus pruebas continúan en
No se considera validado el pipeline remoto por ejecutar verificaciones locales.

Los candidatos del monorepo también tienen namespace: `candidate/axiz-framework/2.0.0/1` o
`candidate/axiz-tools/2.0.0/1`. El manifiesto conserva la etiqueta canónica `2.0.0/1` dentro del
componente; cada pipeline filtra y valida su propio prefijo.

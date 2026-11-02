# Repositorios y versiones

| Repositorio | Contenido | Versión |
|---|---|---|
| `axiz-framework` | Plataforma, capas 1, 2 y 3, motor de generación y plantillas, arquetipos, estándares de código, pruebas de arquitectura | Única para todo el repo (`${revision}`) |
| `axiz-workflows` | Workflows reutilizables, acciones compuestas, presets de Renovate, referencias GitOps | Tags SemVer y el tag mayor móvil (`v1`) |
| `axiz-tools` | `axiz-initializr`, `axiz-cli`, recetas de OpenRewrite | Propia; declara qué versiones del framework soporta |
| `axiz-demo` | Servicios demo de todos los arquetipos y su entorno local | Sigue la última versión del framework |

Las herramientas y el demo consumen el BOM publicado, igual que un servicio real.

## Parents y BOMs

`axiz-parent` es el `pom.xml` raíz del structure: no contiene código y ningún servicio lo usa.
Agrega todos los módulos y define una sola vez lo compartido (Java 25, plugins y versiones,
enforcer, formato, análisis estático, publicación). De él heredan los tres parents publicados:

| Parent | Quién lo hereda | Qué agrega |
|---|---|---|
| `axiz-parent-core` | módulos de la capa 1 y librerías Java puras de los equipos | Prohíbe Spring y Quarkus; sin Boot |
| `axiz-parent-spring` | starters, frameworks y librerías Spring de los equipos | Gestión de Spring Boot, metadata de propiedades, hints AOT; sin fat JAR |
| `axiz-parent-service` | microservicios | Repackage, imagen con Jib (o nativa), perfiles de gates, pruebas de integración, generación de modelos desde el contrato |

Los BOMs son distintos de los parents: un parent dice **cómo** se construye y se hereda; un BOM
dice **qué versiones** usar y se importa. `axiz-dependencies` fija las versiones de terceros (el
único lugar donde se decide una) y `axiz-bom` las de los artefactos `pe.axiz`. Un servicio con su
propio parent puede importar ambos sin heredar nada.

## Ramas, versiones y releases

- `main` contiene lo liberado; `develop` publica SNAPSHOTs de las librerías; `release/*` y
  `hotfix/*` se construyen como `main`; las ramas de trabajo solo corren los checks del PR.
- Versionado SemVer con un tren alineado a la minor de Spring Boot; se soportan la versión actual y
  la minor anterior.
- release-please mantiene un PR de release con la próxima versión y el changelog (Conventional
  Commits); al hacer merge crea el tag `v<x.y.z>`, que publica la versión en Nexus.
- Toda API obsoleta se marca `@Deprecated(forRemoval = true)` con al menos una minor de aviso, y
  cada release trae sus recetas de OpenRewrite (ver [Migración](../migracion/index.md)).
- Nexus Repository es el repositorio de artefactos Maven e imágenes; las credenciales vienen de los
  secretos de CI o de `~/.m2/settings.xml`, nunca de un repositorio.

## Gobierno

El equipo de plataforma es dueño del framework (CODEOWNERS por módulo). Una capacidad nueva entra
por RFC, se construye con el arquetipo `starter` (`axiz new axiz-<capacidad> --archetype starter`)
y llega con su caso de uso en el demo, que una prueba de cobertura obliga a mantener.

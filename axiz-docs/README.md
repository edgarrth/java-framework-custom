# Documentación de Axiz

Guías de uso y operación, catálogos de capacidades, referencia de módulos y dashboards.
El sitio se construye con MkDocs Material y puede publicarse por versión en GitHub Pages.

## Contenido

| Ruta | Contenido |
|---|---|
| [docs/index.md](docs/index.md) | Entrada al sitio y recorridos de lectura |
| [docs/empezar](docs/empezar/index.md) | Preparación del entorno y creación del primer servicio |
| [docs/arquitectura](docs/arquitectura/index.md) | Capas, ejecución, configuración y AWS |
| [docs/capacidades](docs/capacidades/index.md) | Starters, propiedades y ejemplos del catálogo |
| [docs/guias](docs/guias/index.md) | Uso de las capacidades y pruebas |
| [docs/herramientas](docs/herramientas/index.md) | CLI, Initializr y contratos |
| [docs/operacion](docs/operacion/index.md) | CI/CD, despliegue y observabilidad |
| [docs/referencia](docs/referencia/index.md) | Tipos públicos documentados por módulo |
| `scripts/sync_reference.py` | Sincronización desde catálogos y README del framework |
| `scripts/dashboards.py` | Generación de dashboards de Grafana |

## Construir el sitio

Desde `axiz-docs`, instalar las dependencias fijadas y sincronizar la referencia:

```bash
python3 -m venv /tmp/axiz-docs-python
source /tmp/axiz-docs-python/bin/activate
pip install --require-hashes --only-binary=:all: -r requirements.txt
python scripts/sync_reference.py --framework ../axiz-framework --cli-help scripts/cli-help.txt
python scripts/dashboards.py
mkdocs build --strict
mkdocs serve
```

Las páginas marcadas como generadas se obtienen de los catálogos y README del framework.
Editar esas fuentes y ejecutar el sincronizador para que el sitio corresponda a la misma
versión del código. Los enlaces y la estructura del sitio se verifican durante el build.

## GitHub Pages

Pages se configura con GitHub Actions como fuente y el ambiente `github-pages`. El workflow
`docs.yaml` valida cada pull request y, con un tag `axiz-docs-vX.Y.Z`, publica el sitio con las
acciones nativas de Pages.

La URL configurada del sitio es `https://edgarrth.github.io/java-framework-custom/`.

La preparación usa Mike en una copia Git del commit del tag. Conserva `gh-pages`, `CNAME` y
aliases por línea mayor.menor, registrando tag y SHA fuente en `versions.json`. Un patch antiguo
no sustituye a uno posterior; `latest` identifica la línea más reciente.

El publicador verifica el bundle Git, manifiesto, hashes, árbol y ascendencia antes de actualizar
`gh-pages`. El deploy verifica el tar de Pages, identidad del intento productor y vigencia del
commit. El nombre del artefacto incluye run ID e intento; se conserva durante 30 días, sujeto
al máximo configurado en GitHub.

## Recuperación de una publicación

La exclusión `docs-publication` cubre la publicación y Pages. Si una ejecución pendiente es
reemplazada por otra, repetir todos sus jobs o despachar el workflow sobre el tag original.

- Base de `gh-pages` cambiada o artefacto inválido: regenerar todos los jobs desde el mismo tag.
- Fallo exclusivo de Pages: reintentar con los outputs originales si el commit sigue vigente
  y el artefacto conserva identidad, contenido y caducidad válidos.
- Commit desplazado, artefacto ausente o expirado: regenerar desde la base actual.

El intento consumidor no cambia la identidad del productor. El build y la preparación tienen
solo lectura; el publicador y el deploy poseen permisos separados. Consultar
[publicación de versiones](../RELEASING.md) para el orden de componentes y los checks requeridos.

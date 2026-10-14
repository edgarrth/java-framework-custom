# axiz 0.1.0

Plataforma backend para microservicios Java 25 sobre Spring Boot 4.1, EKS y AWS. Cada carpeta es un
repositorio independiente:

| Repositorio | Contenido |
|---|---|
| `axiz-framework` | parents y BOMs, capa 1 (Java puro), capa 2 (starters Spring Boot), capa 3 (estilos), motor de generación, plantillas, arquetipos, estándares y reglas ArchUnit |
| `axiz-tools` | CLI `axiz`, `axiz-initializr` (web y API), recetas de OpenRewrite |
| `axiz-workflows` | workflows reutilizables de GitHub Actions, acciones compuestas, Renovate, referencias GitOps (Argo CD, Kyverno) |
| `axiz-demo` | procesador de pagos de 8 servicios (uno reactivo sobre Aurora con R2DBC) que usa todas las capacidades, entorno local, kind, Bruno, Grafana |

Orden sugerido: `axiz-docs` (`mkdocs serve`) para entender la plataforma, `axiz-framework`
(`./mvnw install`), luego `axiz-tools` y `axiz-demo` (`scripts/demo.sh up`).

Cada repositorio trae su `VERIFICATION.md` con lo que se verificó en la construcción de esta
entrega y lo que queda para CI.

## Correcciones de la revisión externa

Esta entrega incorpora las correcciones del reporte de revisión de 0.1.0 (R2DBC): propiedad y
fencing de llaves de idempotencia e inbox, mensajes ocupados que no se confirman, sagas que
compensan lo que ejecutaron, alcance de llaves por llamante, contratos de stores probados contra
backends reales en CI, wrapper de Maven que verifica la distribución y generador reproducible. El

## Segunda revisión (requisitos de entidad financiera)

Se aplicaron todos los hallazgos F01–F12 y las mejoras estructurales 4, 5 y 7. Los cambios son:

- La autorización vigente se evalúa antes de cualquier replay de idempotencia.
- La autorización es por recurso (`AccessPolicy`), y el servicio puede impedir el arranque si
  quedan comandos o consultas sin cubrir.
- Las sagas no compensan efectos inciertos: verifican con el participante o quedan `IN_DOUBT`.
- La deduplicación usa `source` + `id`.
- El límite diario se reserva de forma atómica y la llave de solicitud del negocio es durable.
- La auditoría puede ser durable mediante el outbox.
- La publicación pasa por un repositorio temporal y se promueve solo después de todos los gates.
- La huella usa la query tal como llega, y la captura de respuestas está acotada.
- El wrapper es estricto aunque haya caché.
- Hay migración del inbox para instalaciones existentes.
- Los ambientes desplegados rechazan stores en memoria.

el perfil operativo; y la migración.

# axiz

axiz es la plataforma backend para construir microservicios **Java 25** sobre **Spring Boot 4.1**,
desplegados en **Amazon EKS** y apoyados solo en los servicios de AWS aprobados. Un equipo crea un
servicio desde un contrato OpenAPI, elige su arquetipo y su estilo arquitectónico, y recibe un
proyecto que compila, pasa sus pruebas y sus reglas de arquitectura, tiene su chart de Helm por
ambiente y su pipeline. El framework resuelve lo transversal (errores, seguridad, observabilidad,
mensajería, idempotencia, auditoría...) para que el código del servicio sea solo negocio.

```bash
axiz new payments-business --archetype business --openapi payments-business.yaml
cd payments-business && local/dev-secrets.sh && docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Qué hay en la plataforma

| Repositorio | Contenido |
|---|---|
| `axiz-framework` | Parents y BOMs, capa 1 (Java puro), capa 2 (starters de Spring Boot), capa 3 (estilos), motor de generación, plantillas, arquetipos y estándares de código |
| `axiz-tools` | `axiz` (CLI) y `axiz-initializr` (web y API), recetas de OpenRewrite para actualizar versiones |
| `axiz-workflows` | Workflows reutilizables de GitHub Actions, acciones compuestas, presets de Renovate y referencias GitOps |
| `axiz-demo` | Procesador de pagos de ocho servicios que usa todas las capacidades, con entorno local, Bruno y Grafana |
| `axiz-docs` | Este sitio, las guías de migración y los dashboards base de Grafana |

## Por dónde empezar

<div class="grid cards" markdown>

- **[Crear un servicio](empezar/index.md)**: del contrato al servicio corriendo en tu máquina.
- **[Arquitectura](arquitectura/index.md)**: capas, configuración, AWS y arquetipos.
- **[Estilos](estructuras/index.md)**: layered, hexagonal, CQRS, EDA, BFF y batch.
- **[Guías](guias/index.md)**: errores, seguridad, eventos, sagas, métricas y más.
- **[Capacidades](capacidades/index.md)**: lo que `axiz add` agrega a un servicio.
- **[Operación](operacion/index.md)**: CI/CD, despliegue GitOps y observabilidad.

</div>

## Principios

- **Contrato primero.** El OpenAPI (y el AsyncAPI para eventos) es la fuente de verdad; los modelos
  se generan al compilar y los cambios incompatibles se detectan en el PR.
- **Reglas verificables.** Toda regla que una herramienta puede revisar rompe el build: formato,
  nulos, capas, anti-patrones, cobertura, secretos, vulnerabilidades.
- **Nada de secretos en el código.** Los secretos vienen de AWS Secrets Manager; las credenciales de
  AWS, de IRSA o EKS Pod Identity; los pipelines usan OIDC.
- **Un solo motor.** La CLI, el initializr y CI generan exactamente el mismo código.
- **Extensible sin tocar la librería.** Tipos de identificador, eventos de métricas, catálogos de
  errores y destinos se configuran en el servicio.

!!! info "Versión de esta documentación"
    El selector de versión del encabezado muestra la documentación de cada versión publicada del
    código de esa versión.

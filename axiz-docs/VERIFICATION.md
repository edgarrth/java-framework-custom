# Verificación de esta entrega

- `mkdocs build --strict` (MkDocs 1.6.1, Material 9.7.7) con validación de enlaces y anclas: sin
  advertencias.
- Páginas de referencia generadas con `scripts/sync_reference.py` desde el `axiz-framework` de esta
  misma entrega; ejemplos de código revisados contra las firmas reales del framework y del demo.
- Dashboards generados con `scripts/dashboards.py`: JSON válido; las consultas usan solo métricas
  y etiquetas que el framework emite (instrumentos de eventos, observaciones, outbox, sagas) o que
  Spring Boot/Micrometer publican.
- review27: `mkdocs build --strict` sin advertencias después de las guías nuevas o cambiadas
  (seguridad, caché y locks, idempotencia, clientes, configuración, despliegue, CI/CD) y de la
  sección "Cambios de la cuarta revisión" de la guía de migración; referencia regenerada desde el
  framework de esta entrega.
- markdownlint corre en CI (`docs.yaml`); no hubo acceso al registro de npm en este entorno.

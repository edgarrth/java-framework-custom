# Soporte

## Versiones admitidas

| Versión | Estado | Correcciones de seguridad | Correcciones de errores |
|---|---|---|---|
| 2.0.x | vigente | sí | sí |

Política de soporte:

- La última versión menor recibe correcciones de errores y de seguridad.
- La versión menor anterior recibe correcciones de seguridad durante seis meses después de publicarse
  la siguiente versión menor.
- Una versión mayor tiene soporte durante doce meses después de publicarse la siguiente versión mayor.
- El fin del soporte se anuncia en las notas de release y en el sitio de documentación con al menos
  tres meses de anticipación. Renovate propone las actualizaciones y `axiz upgrade` aplica las
  convenciones vigentes (`pe.axiz.UpgradeToAxiz`).

El runtime admitido es el que declara la release: Java 25 y la línea de Spring Boot del BOM. Otras combinaciones no tienen
soporte.

## Ayuda

- Preguntas y uso: primero el sitio de documentación (`axiz-docs`) y luego el canal del equipo de
  plataforma.
- Errores y solicitudes de funcionalidades: un issue en este repositorio con la versión, los pasos y el
  comportamiento esperado.
- Problemas de seguridad: nunca en un issue; se sigue `SECURITY.md`.

Esta política cubre el framework, las herramientas para desarrolladores, el sitio de documentación, la
demo de pagos y los workflows compartidos de CI/CD.

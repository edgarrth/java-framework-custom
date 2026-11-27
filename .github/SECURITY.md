# Política de seguridad

Esta política cubre todos los componentes de este repositorio: el framework, las herramientas para
desarrolladores, el sitio de documentación, la demo de pagos y los workflows compartidos de CI/CD.

## Control de release

Toda **vulnerabilidad MEDIUM, HIGH o CRITICAL detectada bloquea el merge y la publicación**, incluidas
las dependencias de desarrollo, las herramientas de CI y cada plataforma de imagen admitida. El mismo
control aplica cuando no existe una corrección en el origen. El componente afectado se actualiza, se
reemplaza o se elimina, y se conserva un escaneo nuevo y completo antes de publicar. Una severidad
desconocida, un reporte ausente o un escáner fallido no es un resultado limpio.

Los plazos de respuesta de abajo rigen la gestión de incidentes; no autorizan publicar un artefacto
afectado. Las aceptaciones de riesgo previas, las mitigaciones y la aprobación del propietario no
pueden eximir de este control. Esos registros se conservan para trazabilidad sin usarlos para suprimir
vulnerabilidades vigentes.

## Reporte de una vulnerabilidad

No se abre un issue público, un pull request ni un hilo de chat por una vulnerabilidad sospechada.

- Se usa el **reporte privado de vulnerabilidades de GitHub** de este repositorio (pestaña Security,
  "Report a vulnerability"), o se escribe al buzón del equipo de seguridad `security@axiz.internal`.
- Se incluye: el módulo y la versión afectados (o el commit), cómo reproducirla, el impacto según se
  entiende y si se conoce explotación activa.
- Nunca se incluyen datos reales de clientes, credenciales ni secretos de producción en el reporte. Si
  un secreto quedó expuesto, se indica cuál para rotarlo primero.

## Qué sucede después

| Severidad (CVSS 4.0 o equivalente) | Acuse de recibo | Triaje y responsable | Corrección publicada o mitigada |
|---|---|---|---|
| Critical | 24 horas | 2 días hábiles | 7 días calendario |
| High | 2 días hábiles | 5 días hábiles | 30 días calendario |
| Medium | 5 días hábiles | 10 días hábiles | siguiente release menor, como máximo 90 días |
| Low | 10 días hábiles | mejor esfuerzo | siguiente release planificada |

1. El equipo de seguridad confirma el reporte y asigna una severidad y un responsable de `CODEOWNERS`.
2. La corrección se prepara en un fork privado o en un aviso de seguridad de GitHub y se publica como
   parche de cada versión admitida (ver `SUPPORT.md`). `@edgarrth` es el único responsable del
   repositorio y de la seguridad. Cualquier otra aceptación de riesgo requiere su aprobación explícita
   y trazable, con alcance exacto, mitigación y vencimiento. Es una aceptación del propietario, no una
   revisión independiente ni una separación de funciones. Un segundo revisor pasa a ser obligatorio
   cuando se incorpora otro mantenedor autorizado. La aceptación no puede eximir del control de release
   anterior ni de los requisitos externos obligatorios.
3. Los servicios que dependen de una versión afectada se identifican a partir de los SBOM y se les
   notifica con el camino de actualización. Renovate abre los pull requests de actualización.
4. El aviso se publica para la organización interna cuando la corrección está disponible (divulgación
   coordinada). Los detalles bajo embargo se comparten solo con las personas que los necesitan.

## Alcance y prioridades

Áreas revisadas con prioridad:

- `axiz-framework`: `core/**/*crypto*`, `core/**/*security*`, `spring/axiz-security-*`, `quality/`.
- `axiz-tools`: `axiz-initializr/` (expuesto a internet) y `axiz-cli/`.
- `axiz-demo`: `services/*/src/main/java/**/security` y `deploy/`.
- `.github`: `workflows/`, `actions/` y `gitops/policies/`.

Las vulnerabilidades de dependencias se siguen de forma automática (Trivy sobre el SBOM, dependency
review en los pull requests, actualizaciones de seguridad de Renovate fuera del calendario semanal);
se reportan aquí solo cuando el propio framework las hace explotables.

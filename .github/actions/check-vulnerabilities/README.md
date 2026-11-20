# Inventario y puerta de vulnerabilidades

Esta acción complementa las puertas MEDIUM/HIGH/CRITICAL, secretos y configuración. Ejecuta Trivy
0.76.2, incluye dependencias de desarrollo y todos los niveles, conserva JSON íntegro y
evalúa `.github/policies/vulnerabilities-v2.json` según su esquema. No aplica `.trivyignore`
ni filtros de severidad o exclusión aportados por el PR. Una ejecución fallida, evidencia
ausente/incompleta, plataforma equivocada o digest distinto bloquea la puerta.

El escaneo de fuentes dispone de 30 minutos y resuelve metadatos Maven locales sin consultas
remotas del escáner. En el monorepo, el pipeline instala primero los POM padre y el BOM del
checkout. El SBOM generado por Maven valida además el grafo efectivo de todos los alcances.
Los escaneos de SBOM e imágenes conservan un presupuesto de 10 minutos por artefacto.

Se invoca desde PR, seguridad programada, construcción de biblioteca y construcción de
servicio. `source` cubre fuentes; `sbom`, el CycloneDX del reactor; `service`, fuentes y cada
imagen final por plataforma y digest. El conjunto esperado deriva de parámetros y metadata
de construcción; no de los reportes recibidos. El digest de fuentes identifica el commit,
no promete representar todos los archivos generados o no versionados del workspace.

## Política de aceptación

Por decisión de Edgar del 2026-11-17, MEDIUM, HIGH y CRITICAL bloquean siempre,
incluidas dependencias de desarrollo. Una excepción, plazo vigente, mitigación o registro
marcado `resolved` no autoriza publicar mientras el escáner siga detectándolas. UNKNOWN
produce evidencia incompleta hasta clasificarla. LOW se inventaría y también bloquea si
existe explotación aplicable o riesgo contextual alto, crítico o urgente.

El inventario conserva responsables, fechas y evidencia para trazabilidad de tratamiento;
no es una lista de exclusiones. Los plazos históricos no sustituyen esta puerta estricta.

`affectedVersions` y `artifactScopes` definen el alcance. La identidad histórica se forma con
PURL del componente e identificadores/aliases transitivos del aviso. Cambiar digest, versión,
scope o ID de fila no reinicia antigüedad. No borrar filas resueltas: conservar su historial.
Un JSON ambiguo, con campos desconocidos/duplicados o fechas incoherentes se rechaza.

## Confianza y revisión

La implementación y el esquema se consumen desde la release inmutable de workflows Axiz.
El inventario y toda su historia se leen por SHA desde la base protegida; HEAD no autoriza
su propia excepción. GitHub debe confirmar protección y revisión obligatoria por CODEOWNERS.
Se consultan reglas activas de rama, incluyendo las de organización. La protección clásica
requiere además permiso de lectura de administración para consultar sus reglas; si no se
pueden comprobar, la puerta falla. El propietario debe conservar cobertura CODEOWNERS para
políticas, acción y workflows, revisiones vigentes y restricciones de bypass apropiadas.

La comprobación de reglas no certifica cómo se aprobó cada commit histórico ni elimina
bypass administrativos. La organización conserva la revisión y evidencia de aprobación.
`approvedBy` en un JSON por sí solo no prueba consentimiento. La configuración inicial usa
`@edgarrth`, elegido por el dueño del proyecto: esto no acredita independencia entre autor
y revisor. GitHub no permite que una persona apruebe su propio PR; no se simula esa revisión.

Provisionar la política y la protección antes de adoptar esta acción en consumidores.
Una rama sin protección o sin inventario base es un error explícito, sin fallback a HEAD.
Las nuevas decisiones propuestas necesitan incorporarse mediante el proceso de revisión;
el PR que las propone no las utiliza para desbloquearse a sí mismo.

GitHub documenta las [reglas activas por rama](https://docs.github.com/en/rest/repos/rules#get-rules-for-a-branch)
y la [protección clásica](https://docs.github.com/en/rest/branches/branch-protection#get-branch-protection).

## Evidencia y pruebas

La carpeta de salida contiene `provenance.json`, `expected.json`, reportes JSON y logs de cada
escaneo y `decision.json`, con razones por hallazgo y propuestas incompletas. Publicar esa
carpeta aun cuando falle la puerta. No confundir exit code 0 del escáner (recolección completa)
con aprobación: `decision.json` y el exit code del evaluador determinan el resultado.

```sh
python3 -m unittest discover -s .github/actions/check-vulnerabilities
```

Las pruebas cubren plazos, clasificación, explotación, versiones/scopes, caducidad, historia
inmutable, alias, plataformas, corrupción de evidencia, filtros heredados y protección remota
simulada. El escaneo y la protección reales necesitan verificarse también en el entorno final.

For exact-version OpenVEX fixes, the image gate queries the GitHub advisory API and requires the
same CVE/GHSA plus both the vendor advisory and the exact patch commit among its references.
It separately recomputes commit ancestry and binds the result to the scanned immutable image ID.
Missing references, upstream verification failures and changed image identity fail the gate.
Source review remains responsible for patch semantics; a commit URL alone never proves a fix.

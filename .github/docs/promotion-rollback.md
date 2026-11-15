# Guía operativa opcional de promoción y rollback

Esta guía describe la promoción y el rollback de builds verificados entre dev y QA.
Los builds de origen ya deben haber terminado y pasado sus gates; no se recompila en deploy.
No se usan credenciales productivas para el ensayo ni se provoca un rollback en producción.

## Datos necesarios

- Repositorio y ref inmutable del workflow consumidor `deploy.yaml`.
- Dos builds aceptados: A (versión anterior) y B (candidata), cada uno con run ID, attempt y
  `manifest-sha256` externo comprobado contra la firma de su productor.
- SHA completo de los values autorizados para cada ambiente y hashes de los manifiestos
  actualmente desplegados; `initial-deployment` solo cuando realmente no existe predecesor.
- Release inmutable y confiable de `axiz-workflows` ya publicada de forma independiente; el
  consumidor debe resolver la versión aprobada sin omitir el trust gate.
- GitOps, Argo CD, credenciales, approvals y environments dev/qa ya configurados según
  [pipelines-v2.md](pipelines-v2.md).

Los datos se obtienen de los resultados firmados del build y del estado GitOps actual. Nunca se
inventan hashes ni se extraen secretos a un informe. Cada ejecución conserva la solicitud y el
resultado firmados, sus run IDs y las revisiones GitOps.

## Secuencia y evidencia

1. Desplegar A en QA como referencia sana. Conservar el resultado firmado y verificar `Synced`,
   `Healthy`, revisión de values, chart e imagen por digest.
2. Desplegar B en dev. Registrar los hashes de los manifiestos y digests exactos aceptados.
3. Promover B a QA mediante otra ejecución de `deploy.yaml` con el mismo build y hash externo.
   El predecesor debe ser A y los values deben ser los de QA. Verificar los mismos digests OCI,
   el estado sano y la respuesta funcional de health/readiness.
4. Revertir QA a A con una tercera ejecución: build de A, hash de B como predecesor y values
   autorizados de A. Verificar que se restauran los digests de A y el servicio vuelve a estar sano.
5. Conservar los tres resultados firmados (B/dev, B/qa, A/qa), la referencia A inicial y el
   comprobante del verificador del manifiesto. Revisar los logs de deploy: no deben contener
   pasos de compilación, Jib, docker build ni empaquetado Helm.

Para disparar cada ejecución se usa `gh workflow run deploy.yaml --repo ORGANIZACION/REPO
--ref REF_INMUTABLE` con los inputs del workflow: `build-run-id`, `build-run-attempt`,
`manifest-sha256`, `environment`, `previous-manifest-sha256` y `values-revision`.
Un resultado de esta guía solo describe las ejecuciones remotas efectivamente realizadas.

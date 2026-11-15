# Configuración de destinos de build y GitOps

Los archivos `*.yaml.template` son fuentes del renderizador. Primero se debe configurar el
destino y generar los manifiestos; no se aplican las plantillas directamente.

Una configuración JSON define el host del registro, su namespace, el repositorio productor,
el repositorio GitOps y los servicios permitidos. El ejemplo
`build-configuration.example.json` tiene campos deliberadamente vacíos y se rechaza hasta
completarlos. `fixtures/build-configuration.json` es un fixture de pruebas con un registro
reservado `.test`, sin acceso remoto ni credenciales.

Para este monorepo, los repositorios de origen y GitOps pueden ser
`edgarrth/java-framework-custom`. El consumidor debe declarar sus destinos reales. No hay un
repositorio adicional implícito. Deploy, rollback y ApplicationSet comparten la ruta canónica
`environments/<ambiente>/<servicio>.yaml`, relativa a la raíz del repositorio GitOps; no existe
un selector de directorio independiente. La rama `main` es una constante compartida entre
el renderizador, deploy y la consulta del predecesor, sin una entrada alternativa.

```bash
python .github/actions/build-manifest/build_destination.py render \
  --configuration /ruta/build-configuration.json \
  --output /ruta/gitops-renderizado
```

El proceso de configuración de la plataforma debe suministrar ese mismo JSON al secreto
`BUILD_DESTINATION` del productor, resolver automático y deploy. Los callers reutilizables transmiten únicamente ese secreto;
no existe un selector alternativo del registro. El JSON no contiene contraseñas. Las credenciales
`REGISTRY_BUILD_USERNAME/PASSWORD` siguen siendo independientes.

La autoridad `registry` es un `host[:puerto]` usado por Docker y Helm para autenticar. `namespace`
es una ruta relativa del registro. Con GHCR, comienza por la cuenta u organización autorizada,
por ejemplo `registry=ghcr.io` y `namespace=edgarrth`. Las referencias resultantes son:

```text
<registry>/<namespace>/builds/<owner>/<repo>/<servicio>@sha256:<digest>
oci://<registry>/<namespace>/build-charts/<owner>/<repo>/<run>/<intento>/<variante>/<servicio>
```

El build y el firmador derivan las rutas desde la misma configuración validada. El firmador
rechaza repositorios, servicios y destinos distintos antes de autenticar. El renderizador
produce ApplicationSet, AppProject y Kyverno con repositorios Git exactos y rutas OCI restringidas
al productor y a los servicios seleccionados. Los charts conservan su digest y los values su
commit inmutable. La admisión exige imágenes por digest y la identidad de firma y procedencia
revisadas; se comprueban contenedores normales e init en una política de Pods/controladores y los
efímeros en otra política específica de `Pod/ephemeralcontainers`.

La validación usa `foreach` y `anyPattern` según la [documentación de Kyverno](https://kyverno.io/docs/policy-types/cluster-policy/validate/).
El formato del namespace de GHCR está documentado por [GitHub](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

## Pruebas de admisión locales

La versión validada de Kyverno es **1.17.0**, fijada junto con el checksum de su archivo de
instalación en `kyverno.json`. `workflows-ci` verifica ese checksum antes de usar el CLI.
El ejecutor comprueba también el SHA-256 del binario extraído antes de ejecutarlo.
La plataforma debe usar esa versión validada o repetir la matriz para otra versión.

```bash
python .github/scripts/test_gitops_policies.py --kyverno /ruta/kyverno --output /tmp/gitops-tests
```

La matriz ejecuta 13 casos sobre las políticas completas renderizadas: Pods con y sin listas
opcionales, init containers, Deployment, CronJob y solicitudes al subrecurso de efímeros.
Los casos permitidos y rechazados se comprueban con el motor de Kyverno. Los resultados
excluidos o saltados no cuentan como cobertura. Una regresión adicional desactiva la
autogeneración y exige que el gate detecte la ausencia de las reglas de controladores.
Los contenedores normales ya admitidos no se reevalúan bajo la lista de imágenes de depuración.

Esta matriz es offline: no consulta registros ni verifica firmas criptográficamente. La
configuración de identidad, procedencia, digest y firmas se verifica por las pruebas del
renderizador.

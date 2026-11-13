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
un selector de directorio independiente.

```bash
python .github/actions/build-manifest/build_destination.py render \
  --configuration /ruta/build-configuration.json \
  --output /ruta/gitops-renderizado
```

El proceso de configuración de la plataforma debe suministrar ese mismo JSON al secreto
`BUILD_DESTINATION` del productor. Los callers reutilizables transmiten únicamente ese secreto;
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
revisadas; se comprueban contenedores normales, init y efímeros.

La validación usa `foreach` y `anyPattern` según la [documentación de Kyverno](https://kyverno.io/docs/policy-types/cluster-policy/validate/).
El formato del namespace de GHCR está documentado por [GitHub](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
Las pruebas locales de renderizado y esquema no acreditan permisos reales de publicación ni una
admisión ejecutada en un clúster remoto.

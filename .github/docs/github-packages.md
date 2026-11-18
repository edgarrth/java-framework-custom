# Artefactos en GitHub

Axiz publica los JAR, POM, BOM y classifiers Maven en
`https://maven.pkg.github.com/edgarrth/java-framework-custom`. Las imágenes y los charts OCI
se publican en `ghcr.io`, usando las coordenadas de `BUILD_DESTINATION`.
GitHub Packages no es un proxy: las dependencias públicas continúan resolviéndose desde
Maven Central. No se necesita un servidor Nexus.

## Consumir Maven localmente

GitHub exige autenticación también para descargar paquetes Maven públicos. Configure un PAT
clásico con `read:packages` en su gestor de secretos y expórtelo como `PACKAGES_TOKEN`, junto
con su usuario en `PACKAGES_USERNAME`. No escriba el valor del token en el POM ni en Git.
El token no debe tener permisos de publicación para compilar o consumir Axiz.

Genere los settings desde la raíz del repositorio; el archivo contiene referencias al entorno,
no los valores de las credenciales:

```bash
python3 .github/actions/setup-maven/settings.py \
  --settings /tmp/axiz-maven-settings.xml --temporary /tmp/axiz-maven \
  --packages-url https://maven.pkg.github.com/edgarrth/java-framework-custom
mvn -s /tmp/axiz-maven-settings.xml -f axiz-framework/pom.xml verify
```

Para un proyecto consumidor, use los mismos settings desde el primer comando Maven: el parent
de Axiz debe poder resolverse antes de leer la configuración heredada. El server Maven se llama
`github`, tanto para releases como para snapshots. Antes de la primera publicación se puede
instalar el framework en el repositorio Maven local o consumir una candidata firmada.

## Actions y publicación

Los jobs declaran sus permisos individualmente. La lectura usa `packages: read`; publicar
Maven en `lib-deploy` y escribir imágenes/charts/firmas en GHCR requiere `packages: write`.
`GITHUB_TOKEN` es temporal y se limita al repositorio de la ejecución. El despliegue del servicio
solo lee GHCR y usa una GitHub App separada para escribir el estado GitOps.

La compilación de servicios tiene únicamente lectura de paquetes. Jib genera archivos OCI
por plataforma y Helm empaqueta el chart; un job aislado comprueba en Actions que ese worker
terminó sus gates, verifica el inventario, los hashes y las plataformas y publica esos mismos
bytes. El publicador no ejecuta Maven ni código de la aplicación. La firma ocurre en otro job.
Los pull requests compilan y escanean los archivos locales, sin publicar ni firmar productos.

Para dependencias Maven de otro repositorio configure los secretos opcionales
`PACKAGES_READ_USERNAME` y `PACKAGES_READ_TOKEN`. Si no están presentes se usa `github.actor`
y `github.token`; esto requiere que dicho token tenga acceso al paquete. La variable opcional
`AXIZ_PACKAGES_URL` cambia el repositorio de dependencias; no cambia el destino de publicación.
No se heredan secretos en bloque entre workflows.

`lib-deploy` recibe `destination: github-packages` y publica en el repositorio del llamador,
`https://maven.pkg.github.com/<owner>/<repository>`. Usa el manifiesto firmado, verifica los
hashes antes y después de subir cada archivo y rechaza un contenido distinto en coordenadas
existentes. Conserva la separación entre construir y promover; no recompila durante el deploy.
Las promociones se serializan por repositorio y destino. Un repositorio no debe autorizar
publicadores paralelos fuera de ese contrato ni borrar versiones publicadas para reemplazarlas.

Para un `mvn deploy` manual de una librería generada, configure explícitamente
`-Daxiz.packages.url=https://maven.pkg.github.com/<owner>/<repository>` y un token de publicación.
El flujo empresarial recomendado es la promoción del bundle aprobado desde Actions.

## GHCR

El ejemplo [build-configuration.example.json](../gitops/build-configuration.example.json)
define `registry: ghcr.io` y `namespace: edgarrth`. Configure ese JSON como `BUILD_DESTINATION`
para el monorepo; cada consumidor debe usar su propio owner, repositorios y lista de servicios.
Los destinos incluyen repositorio, servicio y ejecución para separar los productos.

Las imágenes se despliegan por digest y los charts conservan el digest aprobado. Las etiquetas
no sustituyen esa identidad. Los paquetes nuevos pueden ser privados; conceda acceso de lectura
a los repositorios consumidores y configure `imagePullSecrets` y las credenciales OCI de Argo CD
cuando el clúster deba descargar paquetes privados. Un `GITHUB_TOKEN` de Actions no sirve como
credencial permanente del clúster.

## Verificación

El gate de API consulta la API autenticada de GitHub, pagina paquetes y versiones y filtra el
repositorio y las coordenadas exactas. Un error 401, 403, 404 o de red falla el gate; nunca se
interpreta como ausencia de versiones. `--inventory-only` prepara evidencia y no aprueba releases.
La publicación real requiere gates de calidad y seguridad satisfactorios y el workflow inmutable
publicado; configurar el registro no sustituye esas comprobaciones.

Las pruebas AWS de esta etapa usan exclusivamente LocalStack. No se invoca AWS remoto ni se
presenta la emulación como evidencia de IAM, KMS o PCI DSS en producción.

Referencias: [registro Maven](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry),
[GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
y [API de paquetes](https://docs.github.com/en/rest/packages/packages).

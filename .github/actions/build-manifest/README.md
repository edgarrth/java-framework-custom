# Identidad del build

`build-manifest.json` describe un build verificado. Las librerías Maven y los servicios tienen esquemas
estrictos separados. `parameters_sha256` usa RFC 8785; el hash del manifiesto describe sus bytes
exactos. El despliegue recibe el hash esperado mediante la salida confiable del build o la aprobación,
con independencia del bundle descargado. Un checksum dentro del archivo no establece confianza.

Un bundle contiene el manifiesto canónico, su bundle de firma keyless de cosign, los resultados de los
controles, el SBOM y la procedencia. Las librerías también incluyen `maven/` con cada archivo del
staging y `SHA256SUMS`. GitHub Packages genera los metadatos del repositorio; la promoción no debe
sobrescribir su índice de versiones existente.

Los workers de servicios solo leen paquetes. Transfieren los archivos OCI y un chart empaquetado a un
publicador aislado, que verifica la autoridad de Actions, los hashes de los archivos y los descriptores
de plataforma antes de subir los bytes originales a GHCR. Nunca ejecuta código del proyecto. El
producto firmado conserva el registro `compile-authority`; los pull requests compilan y escanean sin
publicar ni firmar.

`consume-build` verifica la ejecución y el intento explícitos del productor, la release inmutable de
workflows permitida, la identidad de la firma, el repositorio y el commit de origen, cada control
obligatorio y todos los bytes del staging. Los artefactos ausentes o expirados fallan. El bundle
verificado es de solo lectura.

La política de firmas usa la identidad de workflow reutilizable documentada por
[Fulcio](https://github.com/sigstore/fulcio/blob/main/docs/oidc.md) y la
[verificación de blobs de cosign](https://docs.sigstore.dev/cosign/verifying/verify/).

La suite de regresión se ejecuta en un entorno Python aislado:

```sh
python3 -m venv /tmp/axiz-manifest-tests
/tmp/axiz-manifest-tests/bin/pip install --require-hashes -r .github/actions/build-manifest/requirements.txt
/tmp/axiz-manifest-tests/bin/python -m unittest discover -s .github/actions/build-manifest -p 'test_*.py'
```

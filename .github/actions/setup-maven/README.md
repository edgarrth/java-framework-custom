# setup-maven

Configura JDK 25 y un repositorio de GitHub Packages. Las credenciales quedan como referencias a
`PACKAGES_USERNAME` / `PACKAGES_TOKEN` en el entorno del job; sus valores nunca se guardan en settings.
Los builds entregan credenciales de solo lectura. Los jobs aislados de despliegue publican con su
propio `GITHUB_TOKEN` temporal y el permiso `packages: write`.

Para un candidato, primero se ejecuta `consume-build` con la ejecución y el intento explícitos del
productor y el SHA-256 del manifiesto obtenido fuera del artefacto. Su bundle verificado se pasa como
`candidate-bundle`, junto con `candidate-manifest-sha256` y `candidate-source-repository`.

El modo candidato vuelve a validar todos los bytes de Maven, verifica una copia separada y la deja de
solo lectura. El perfil activo `axiz-github` ordena la copia `axiz-candidate` antes del registro remoto.
GitHub Packages no replica Maven Central. El repositorio local de Maven es un directorio vacío nuevo
bajo `RUNNER_TEMP` en cada invocación, y la caché de Maven de setup-java/GraalVM está desactivada. Los
resultados del build original nunca se modifican. La acción devuelve las rutas aisladas del
repositorio local y del repositorio candidato.

La suite de regresión se ejecuta con los requirements de `../build-manifest`:

```bash
python -m unittest discover -s .github/actions/setup-maven -p 'test_*.py'
```

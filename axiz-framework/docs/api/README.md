# Política de API pública

La compatibilidad de la API pública se comprueba con
[japicmp](https://siom79.github.io/japicmp/), integrado en el perfil Maven `api-compat`. Cada
módulo se compara con la misma versión ya publicada en GitHub Packages, que se resuelve como una
dependencia Maven normal. Un cambio incompatible a nivel binario o de código fuente rompe el
build.

```bash
./mvnw --bootstrap -B -ntp -Papi-compat -DskipTests verify -Daxiz.api.baseline=2.0.0
```

Antes de la primera publicación no existe una versión base y la comparación no se ejecuta. A
partir de entonces, el workflow `framework.yaml` toma como base la última versión registrada en
`.release-please-manifest.json`. Los módulos nuevos, que no existen en la versión base, se
omiten. Un cambio incompatible intencional se publica como una versión mayor nueva.

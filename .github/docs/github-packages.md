# Artefactos en GitHub

Axiz publica los JAR, POM y BOM en GitHub Packages
(`https://maven.pkg.github.com/edgarrth/java-framework-custom`), y las imágenes y los charts en
GHCR (`ghcr.io/<owner>`). GitHub Packages no es un proxy: las dependencias públicas siguen
resolviéndose desde Maven Central.

## Consumir Maven localmente

GitHub exige autenticación también para descargar paquetes Maven públicos. Se crea un token
clásico con permiso `read:packages`, se guarda en el gestor de secretos y se exporta como
`GITHUB_TOKEN`, junto con el usuario en `GITHUB_ACTOR`. El valor nunca se escribe en el POM ni en
Git. En `~/.m2/settings.xml` se declara el servidor `github`, que es el id que usan los POM de
Axiz:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Antes de la primera publicación, el framework se instala en el repositorio Maven local con
`scripts/build.sh --skip-tests` desde la raíz del repositorio.

## En GitHub Actions

`actions/setup-java` escribe ese mismo servidor con `server-id: github` y las credenciales del
`GITHUB_TOKEN` de la ejecución. La lectura usa `packages: read`. Publicar en GitHub Packages o en
GHCR requiere `packages: write` solo en el job de publicación, junto con `id-token: write` y
`attestations: write` para las atestaciones de procedencia. Ningún job hereda secretos en bloque.

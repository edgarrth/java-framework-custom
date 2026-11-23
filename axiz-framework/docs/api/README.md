# Política de API pública

El inventario de API pública registra las firmas y constantes públicas y protegidas de cada JAR publicado. CI compara cada build con este inventario y aplica el modo de publicación seleccionado en `release-policy.json`. La release inicial del framework es 2.0.0.

`release-policy.json` selecciona de forma explícita uno de dos modos:

- `first-release`: solo la versión 2.0.0, sin línea base. El control se autentica en la API de GitHub y enumera los paquetes Maven y sus versiones,
  filtrando el repositorio de origen exacto y las coordenadas del framework. Una release del framework ya publicada,
  una consulta fallida o una respuesta ambigua rechazan este modo. Los repositorios candidatos, las cachés locales
  de Maven y los productos no relacionados no cuentan como versiones publicadas del framework.
- `published-baseline`: requiere una versión del framework realmente anterior en ese repositorio final.
  El control descarga primero esa línea base desde el repositorio final a un directorio separado y
  registra sus hashes. No confía en las cachés de Maven candidatas ni locales. El perfil estricto `api-compat`
  de Maven debe producir reportes nuevos de Japicmp para cada JAR
  publicado. Si la línea base no se resuelve, falla; nunca pasa al modo first-release.

En ambos modos, las firmas de los archivos de clase se extraen con `javap -protected -constants` y se
comparan con el inventario aprobado `public-api.json`. Agregar o quitar módulos o cambiar firmas
públicas o protegidas requiere revisión. El registro del control guarda las firmas observadas, su
SHA-256 y el SHA-256 de cada JAR real, además del origen, la ejecución, el intento y el resultado. CI
conserva ese registro durante 90 días y sigue siendo un control obligatorio antes de la promoción.

Para preparar la revisión local después de un build:

```bash
python3 .github/scripts/check-api-compatibility.py --inventory-only
```

Este comando de forma explícita **no** supera el control de release. Las firmas exportadas se revisan
antes de actualizar `public-api.json`; nunca se aprueban cambios solo para silenciar CI. El control normal
requiere `PACKAGES_URL`, con credenciales de lectura en `PACKAGES_USERNAME`/`PACKAGES_TOKEN`. El token debe tener `read:packages` y el principal debe
poder enumerar los paquetes Maven de GitHub y sus versiones y hacer GET de los artefactos publicados; no necesita permisos de escritura:

```bash
python3 .github/scripts/check-api-compatibility.py
```

Después de la primera publicación inmutable, la política cambia a `published-baseline`, se avanza la
versión objetivo y la línea base se fija en la 2.0.0 realmente publicada. Sus artefactos publicados se
mantienen inmutables. Los builds producen candidatos firmados; el despliegue promueve de forma
independiente esos mismos bytes sin recompilar. Las comprobaciones de firma preservan la separación
entre construir y promover artefactos.

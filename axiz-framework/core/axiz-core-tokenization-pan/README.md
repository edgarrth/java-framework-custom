# axiz-core-tokenization-pan

Perfil PAN y fachada tipada sobre el contrato genérico de tokenización.

Este módulo es dueño de la API `pe.axiz.core.tokenization.pan`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-tokenization-pan</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `PanTokenProfile`
- `PanTokenizer`

## Límites

El core es dueño de tipos de valor, políticas o puertos y no realiza I/O de red ni de sistema de
archivos. La composición con Spring y los proveedores concretos pertenecen a módulos separados.
Los consumidores se prueban contra estos contratos sin arrancar Spring.

La [guía de tokenización](../../../axiz-docs/docs/guias/tokenizacion.md) describe los límites entre
cliente y bóveda, los perfiles, la autorización, la retención y el flujo independiente de protección
de campos.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl core/axiz-core-tokenization-pan -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.

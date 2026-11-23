<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-http-jdk

Transportes HTTP del JDK para OAuth y SOAP.

Este módulo es dueño de la API `pe.axiz.adapter.http.jdk`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-http-jdk</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `ClientCredentialsTokenProvider`
- `JdkSoapTransport`

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl adapters/axiz-adapter-http-jdk -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.

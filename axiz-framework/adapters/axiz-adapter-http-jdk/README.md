# axiz-adapter-http-jdk

JDK HTTP transports for OAuth and SOAP.

This module owns the `pe.axiz.adapter.http.jdk` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-http-jdk</artifactId>
</dependency>
```

## Types

Public types declared by this module:

- `ClientCredentialsTokenProvider`
- `JdkSoapTransport`

## Verification

From `axiz-framework`:

```bash
./mvnw -pl adapters/axiz-adapter-http-jdk -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.

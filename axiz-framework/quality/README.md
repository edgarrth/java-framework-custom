# Calidad

Los parents Maven aplican los estándares comunes; los servicios incorporan las reglas de
arquitectura y las utilidades de prueba que necesitan. Los módulos internos verifican el
framework y sus implementaciones de persistencia.

| Módulo | Uso |
|---|---|
| [axiz-code-standards](axiz-code-standards/README.md) | Configuraciones compartidas de Spotless, Checkstyle, SpotBugs, Semgrep, Spectral y Gitleaks, referenciadas por los parents |
| [axiz-archunit-rules](axiz-archunit-rules/README.md) | Reglas de arquitectura que ejecutan las pruebas de los servicios |
| [axiz-test-support](axiz-test-support/README.md) | Reloj controlable, publicadores de prueba, fixtures y utilidades para probar capacidades |
| [axiz-test-containers](axiz-test-containers/README.md) | Imágenes corregidas de infraestructura para pruebas, compartidas con los entornos locales generados |
| [axiz-store-contract-tests](axiz-store-contract-tests/README.md) | Contratos reutilizables de propiedad, expiración y retención para stores de idempotencia e inbox |
| [axiz-architecture-tests](axiz-architecture-tests/README.md) | Verificación interna de las capas y dependencias del framework |
| [axiz-store-integration-tests](axiz-store-integration-tests/README.md) | Contratos contra PostgreSQL, Valkey y DynamoDB mediante Testcontainers; no se publica |
| [axiz-coverage-report](axiz-coverage-report/README.md) | Cobertura agregada del reactor; no se publica |

Desde `axiz-framework`, ejecutar los controles de calidad y exigir Docker para las
integraciones:

```bash
./mvnw --bootstrap clean verify -Pquality -Daxiz.it.requireDocker=true
```

Cada módulo describe sus pruebas y dependencias. La [guía de pruebas](../../axiz-docs/docs/guias/pruebas.md)
explica cómo aplicar estos controles en un servicio.

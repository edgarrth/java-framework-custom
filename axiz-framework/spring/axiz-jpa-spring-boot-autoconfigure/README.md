# axiz-jpa-spring-boot-autoconfigure

Provider-neutral JPA conventions. Consumers select `axiz-jpa-spring-boot-starter`;
`axiz-bom` manages its version.

## Responsibilities

- `AxizJpaAutoConfiguration`: context-owned entity listener, request auditor and clock;
  validates explicitly declared encryption contexts.
- `RequestAuditorProvider`: authenticated subject or client ID; system identity when absent.
- `AxizJpaEnvironmentPostProcessor`: no open-session-in-view, UTC, batching, schema validation,
  Flyway locations and bounded Hikari pool defaults. PostgreSQL TLS uses `verify-full`.
- Schema creation/update is restricted to explicit `local`, `test` or `ci` environments.
  Deployed applications apply reviewed Flyway migrations and validate the schema.

## Sensitive diagnostics

SQL stdout and Hibernate SQL/JDBC loggers (including bind/extract values, batches and constraint
errors) default to OFF. Outside `local`, `test` and `ci`, enabling them fails startup: database
error details can contain full rejected values. Use sanitized problem codes and Axiz operation
logs. Custom logging configurations and programmatic SessionFactory properties remain the
application's responsibility; exercise the generated sensitive-data canary with them.

## Optional providers

AWS failover, IAM authentication and workload identity belong to the separate
[`axiz-jpa-aws-spring-boot-autoconfigure`](../axiz-jpa-aws-spring-boot-autoconfigure/README.md).
This neutral module does not pull the AWS SDK or JDBC wrapper.

## Tests

From `axiz-framework`, run `./mvnw -pl spring/axiz-jpa-spring-boot-autoconfigure -am test`.
Generated JPA services also verify a real PostgreSQL constraint failure with
`./mvnw -Dtest=SensitiveDataCanaryTest test` (Docker required).

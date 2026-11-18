# axiz-saga-jdbc-spring-boot-autoconfigure

Opt-in JDBC saga persistence. Install `axiz-saga-jdbc-spring-boot-starter` for the
neutral saga policies plus this integration. Requires a `DataSource`;
`axiz.saga.store=jdbc` selects JDBC. No provider is installed by the base starter.

`axiz.saga.table` defaults to `axiz_saga`; `initialize-schema=EMBEDDED` creates
only embedded schemas. Production migrations provision the schema explicitly.
`axiz.saga.context-protection=envelope` requires the application's `EnvelopeEncryptor`.

Run `mvn -pl spring/axiz-saga-jdbc-spring-boot-autoconfigure -am test`.

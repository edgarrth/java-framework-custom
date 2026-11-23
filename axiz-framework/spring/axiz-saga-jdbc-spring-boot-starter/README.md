# axiz-saga-jdbc-spring-boot-starter

Persistencia JDBC opcional de sagas. Se instala `axiz-saga-jdbc-spring-boot-starter` para obtener las
políticas neutrales de saga más esta integración. Requiere un `DataSource`;
`axiz.saga.store=jdbc` selecciona JDBC. El starter base no instala ningún proveedor.

`axiz.saga.table` vale `axiz_saga` por defecto; `initialize-schema=EMBEDDED` crea
solo esquemas embebidos. En producción las migraciones aprovisionan el esquema explícitamente.
`axiz.saga.context-protection=envelope` requiere el `EnvelopeEncryptor` de la aplicación.

Las pruebas se ejecutan con `mvn -pl spring/axiz-saga-jdbc-spring-boot-autoconfigure -am test`.

# axiz-jdbc-spring-boot-autoconfigure

Shared JDBC transport guard, installed by the capabilities that use this technology. It does not create business clients or add a new store selector.

Outside explicit local/test/ci environments, require TLS with certificate and hostname verification. Local plaintext additionally requires `axiz.transport.allow-insecure-data-stores=true`; the environment alone is insufficient. Never include connection URLs, credentials or secret names in failure diagnostics.

Validate final client settings before the first connection. Custom factories that connect during construction must invoke their transport validation before constructing/starting clients; a post-processor cannot undo earlier I/O. JDBC extension point: `JdbcDataSourceTlsValidator`; PostgreSQL URL/properties validation: `JdbcTransportSecurity.validate`. Flyway validates its own final URL and properties before migration. CA configuration is destination-specific; no RDS CA is imposed on other databases.

## Transport contract

Managed clients require encryption and certificate and hostname verification. The final native
options are checked before managed initialization; property names alone are not a transport proof.
`axiz.transport.allow-insecure-data-stores=true` only permits an explicit `local`, `test` or `ci`
environment. It never weakens production validation. Configure trust material for each destination.

Hikari JDBC URLs take precedence over data-source properties. Flyway own connection settings
are checked independently. Custom data sources supply `JdbcDataSourceTlsValidator`; their factory
must validate before a constructor or warmup can connect. Return Hikari pools unstarted.

Boot's separate Flyway `SimpleDriverDataSource` is supported for the native PostgreSQL and AWS
wrapper drivers. Its effective URL and connection properties are validated without opening a
connection. Other drivers and custom subclasses use the explicit validator extension point.

The `datasource-micrometer` observation wrappers delegate validation to their actual data source,
including Spring and JDK proxies. The guard checks the target's current options again before
Flyway uses it; wrapping an insecure or opaque source does not authorize it. Missing targets and
wrapping cycles fail startup. Validation never calls `getConnection`.

PostgreSQL uses the native `LibPQFactory` or `DefaultJavaSSLFactory` with verified TLS.
Arbitrary `sslfactory` and `sslhostnameverifier` implementations cannot establish that guarantee
from a mode string and are rejected by the native validator. A custom data-source integration
must explicitly validate its trust material and hostname verification through the extension point.

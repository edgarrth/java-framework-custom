# axiz-jdbc-spring-boot-autoconfigure

Lado Spring de JDBC para las capacidades que lo usan (outbox, inbox, saga, sequence, scheduling):
conexiones y ejecutores ligados a la transacción, inicialización explícita del esquema, dialectos SQL y la
protección de transporte compartida. No crea clientes de negocio ni agrega un nuevo selector de almacén.

## Tipos principales

| Tipo | Propósito |
|---|---|
| `SpringConnectionProvider` | Proveedor de conexiones ligado a las transacciones de Spring: dentro de una transacción devuelve la conexión de la transacción y la deja abierta al liberarla. |
| `SpringTransactionRunner` | Ejecuta trabajo en una transacción de Spring nueva o existente; las excepciones checked también provocan rollback. |
| `SchemaInitializer` | Crea las tablas del framework desde `axiz/sql/<name>-<dialect>.sql` (desarrollo y pruebas). |
| `SqlDialects` | Detecta el producto de base de datos para elegir los dialectos SQL (PostgreSQL en clústeres, H2 en pruebas). |
| `JdbcDataSourceTlsValidator` | Punto de extensión que valida el transporte de un data source personalizado antes de que se conecte. |
| `AxizJdbcTransportAutoConfiguration` | Validación TLS de cierre seguro (fail-closed) de los clientes JDBC gestionados y de Flyway. |

Los almacenes cuyas escrituras deben ser visibles de inmediato para otros workers (estado de sagas y
leases) usan `AutoCommitConnectionProvider` de `axiz-kit-jdbc` en lugar de `SpringConnectionProvider`.

## Protección del transporte

Fuera de los entornos explícitos local/test/ci se exige TLS con verificación del certificado y del hostname. El texto plano local requiere además `axiz.transport.allow-insecure-data-stores=true`; el entorno por sí solo no basta. Los diagnósticos de fallo nunca incluyen URLs de conexión, credenciales ni nombres de secretos.

La configuración final del cliente se valida antes de la primera conexión. Las fábricas personalizadas que se conectan durante su construcción deben invocar la validación de transporte antes de construir o iniciar los clientes; un post-processor no puede deshacer la I/O previa. Punto de extensión JDBC: `JdbcDataSourceTlsValidator`; validación de URL y propiedades de PostgreSQL: `JdbcTransportSecurity.validate`. Flyway valida su propia URL y sus propiedades finales antes de migrar. La configuración de CA es específica de cada destino; no se impone una CA de RDS a otras bases de datos.

## Contrato de transporte

Los clientes gestionados exigen cifrado y verificación de certificado y de nombre de host. Las
opciones nativas finales se comprueban antes de la inicialización gestionada; los nombres de
propiedad por sí solos no prueban el transporte. `axiz.transport.allow-insecure-data-stores=true` solo admite un ambiente
`local`, `test` o `ci` explícito y nunca debilita la validación de producción. El material de confianza se
configura para cada destino.

Las URLs JDBC de Hikari tienen precedencia sobre las propiedades del data source. La configuración de
conexión propia de Flyway se comprueba por separado. Los data sources personalizados aportan
`JdbcDataSourceTlsValidator`; su fábrica debe validar antes de que un constructor o un precalentamiento
pueda conectarse. Los pools de Hikari se devuelven sin iniciar.

Se admite el `SimpleDriverDataSource` separado de Flyway de Boot para los drivers nativos de PostgreSQL y
el wrapper de AWS. Su URL efectiva y sus propiedades de conexión se validan sin abrir una conexión. Otros
drivers y subclases personalizadas usan el punto de extensión explícito del validador.

Los wrappers de observación de `datasource-micrometer` delegan la validación a su data source real,
incluidos los proxies de Spring y del JDK. La protección vuelve a comprobar las opciones actuales del
destino antes de que Flyway lo use; envolver una fuente insegura u opaca no la autoriza. Los destinos
faltantes y los ciclos de envoltura hacen fallar el arranque. La validación nunca llama a `getConnection`.

PostgreSQL usa el `LibPQFactory` nativo o `DefaultJavaSSLFactory` con TLS verificado. Las
implementaciones arbitrarias de `sslfactory` y `sslhostnameverifier` no pueden establecer esa garantía a
partir de una cadena de modo y el validador nativo las rechaza. Una integración de data source
personalizada debe validar explícitamente su material de confianza y la verificación del hostname
mediante el punto de extensión.

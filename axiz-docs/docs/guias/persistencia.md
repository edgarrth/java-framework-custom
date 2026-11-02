# Persistencia

## Aurora PostgreSQL (JPA)

`axiz add jpa` (incluida al elegir persistencia `aurora`) trae Spring Data JPA, Flyway y el AWS
JDBC wrapper (autenticación IAM, failover rápido y separación lectura/escritura, activables por
ambiente con `axiz.jpa.aws-wrapper.*`).

- **Esquema por Flyway** (`src/main/resources/db/migration/V<n>__*.sql`), Hibernate solo valida
  (`ddl-auto: validate`); `open-in-view` desactivado. `axiz generate migration` y `axiz add`
  numeran después de la última versión.
- `BaseEntity`: id UUIDv7 asignado por la aplicación (ordenado en el tiempo, amigable con índices,
  conocido antes del insert) y bloqueo optimista.
- `AuditableEntity`: columnas de creación y modificación (instantes UTC y principal autenticado).
- `EncryptedConverter`: cifra una columna con AES-256-GCM y llaves rotables:

```java
@Convert(converter = EncryptedConverter.class)
private String debtorAccount;

private String debtorAccountIndex;   // BlindIndex: búsqueda por igualdad sin descifrar
```

Nunca se descifra mutando entidades gestionadas: el converter lo hace al leer y escribir.

## Aurora PostgreSQL desde servicios reactivos (R2DBC)

Con `--stack reactive --persistence aurora` el servicio recibe `axiz add r2dbc`: Spring Data R2DBC
sobre r2dbc-postgresql con pool, sin JDBC en el camino de las requests.

- **Mismo esquema, mismo Flyway**: las migraciones corren por JDBC al arrancar; la URL de Flyway se
  deriva de `spring.r2dbc.url` (`axiz.r2dbc.flyway-from-r2dbc`).
- **Pool preparado para Aurora**: validación remota al prestar cada conexión, vida máxima de 10
  minutos (así un failover o un token vencido nunca dejan conexiones muertas) y espera acotada al
  pedir una conexión (`spring.r2dbc.pool.*`, sobrescribibles).
- **IAM** (`axiz.r2dbc.iam.enabled: true`): cada conexión nueva, y Flyway, se autentican con un
  token firmado con las credenciales del pod (IRSA o EKS Pod Identity), sin contraseña en el
  secret store; exige TLS (`sslMode=require` por defecto con IAM).
- **Endpoint lector** (`axiz.r2dbc.reader.url`): un pool aparte expuesto como `ReaderDatabase`
  para consultas que toleran el retraso de réplica; nunca participa en transacciones.
- `AuditableRow`: id UUIDv7, `@Version` y columnas de auditoría llenadas con el llamador de la
  request (desde el Reactor Context).
- Columnas `EncryptedText` (AES-256-GCM, el mismo contexto que el converter JPA: ambos stacks leen
  los datos del otro) y `Money` (texto `"PEN 150.00"`).
- `ReactiveSql`: SQL con parámetros nombrados que se une a la transacción reactiva del llamador,
  para consultas que no conviene derivar de un repositorio.

`R2dbcSql` y `R2dbcTransactionRunner` aíslan sus conexiones por identidad de `ConnectionFactory`.
Dos instancias que usan la misma fábrica pueden compartir la conexión activa; una fábrica distinta
conserva su propia conexión, incluso dentro de una transacción anidada. El contexto interno de
`R2dbcSql.inTransaction` contiene las conexiones por fábrica y no debe inyectarse manualmente como
una conexión global. Las transacciones de fábricas distintas se confirman de forma independiente;
no constituyen una transacción distribuida.

```java
@Table("payment_history")
class PaymentHistoryRow extends AuditableRow {
  private EncryptedText creditorCci;
  private Money amount;
  ...
}

interface PaymentHistoryRepository extends ReactiveCrudRepository<PaymentHistoryRow, UUID> {
  Mono<PaymentHistoryRow> findByPaymentId(String paymentId);
  Flux<PaymentHistoryRow> findByCustomerIdOrderByLastChangeAtDesc(String customerId, Pageable page);
}
```

Las transacciones las abre el pipeline (CQRS reactivo o handler EDA reactivo) con el
`R2dbcTransactionManager`; dentro de ellas los repositorios, `DatabaseClient`, `ReactiveSql` y el
outbox (`ReactiveTransactionalEvents`) comparten la conexión. En las pruebas,
`PostgresTestConfiguration` sirve a JDBC, R2DBC y Flyway con el mismo contenedor.

## DynamoDB

`axiz add dynamodb` trae el enhanced client y `DynamoRepository<T>`:

```java
DynamoRepository<Beneficiary> repo = repositories.create("beneficiaries", Beneficiary.class);
repo.findById(customerId, beneficiaryId);
Page<Beneficiary> page = repo.query(customerId, 20, cursor);   // cursor opaco (LastEvaluatedKey)
repo.insert(beneficiary, "customerId");                       // condicional: falla si ya existe
repo.update(beneficiary);                                     // bloqueo optimista con @DynamoDbVersionAttribute
```

- Tablas con prefijo por ambiente (`axiz.dynamodb.table-prefix`), creadas por Terraform.
- Paginación por **cursor**: `CursorCodec` codifica la última llave evaluada en base64url; el demo
  además valida que el cursor pertenezca al cliente que pide.
- `EncryptedAttributeConverter` y `MoneyAttributeConverter` (el dinero se guarda como texto
  `"PEN 150.00"`, nunca como flotante).
- Consultas sobre GSI y LSI, transacciones con `transact`.

## Dinero, tiempo e identificadores

- `Money` (monto y moneda ISO 4217, redondeo de la moneda) y `CurrencyConverter`.
- `BusinessCalendar`: zona, fines de semana y feriados (`axiz.foundation.calendar.holidays` en el
  ConfigMap compartido), días hábiles, cortes horarios.
- `IdentifierRegistry`: DNI, RUC, CE, CCI, PAN, IBAN, E.164 y email con dígitos verificadores;
  tipos propios por SPI (`IdentifierTypeProvider`) o variantes (`cci-strict` en el demo).

### Contrato monetario del framework

`axiz-core-money` es independiente de Spring. `Money` conserva la escala de la moneda y un máximo
de 30 dígitos enteros en **todas** sus fábricas y resultados: si una operación excede el rango,
falla con `MoneyErrors.INVALID_AMOUNT` antes de devolver un valor. Todo resultado exitoso admite
el recorrido `Money.parse(value.toString())`, incluido el acarreo al redondear y las asignaciones.

En fronteras externas —HTTP, mensajes, archivos, configuración y lectura de importes persistidos—
se usa `Money.ofExact(amount, currency)`. Acepta `150.5` y `150.000` en PEN como `150.50` y `150.00`,
pero rechaza `150.005`. Para cálculos que requieren redondeo se mantienen `Money.of`, `multiply`,
`divide` y `percentage`, con una política explícita (por defecto `HALF_EVEN`). Las entradas se
acotan antes de expandir exponentes; las fábricas, divisores y porcentajes admiten hasta 100
posiciones decimales dentro del rango documentado por `Money`.

El módulo `axiz-foundation` adapta este contrato a Jackson: lee los tokens numéricos directamente
como `BigDecimal`, sin pasar por `double`, y acepta también importes textuales. Por ejemplo,
`9007199254740992.01` conserva sus centavos en ambos formatos. La salida sigue siendo textual para
que los clientes no introduzcan coma flotante. Campos monetarios duplicados, tipos incorrectos,
monedas ausentes y valores inexactos se rechazan. La política pertenece al framework; las aplicaciones
solo aplican su API en los adaptadores.

Al migrar, revisar consumidores que dependían del redondeo implícito de entradas o de resultados
fuera del rango. La demo ilustra este contrato, sin introducir una implementación monetaria propia.

## Multi-tenancy

`axiz add tenancy`: el tenant sale del token (o de un header de confianza puesto por la
plataforma, nunca por el cliente), se guarda en `TenantContext` y se propaga a logs, métricas,
eventos y llamadas salientes. Con `axiz.tenancy.required` las requests sin tenant se rechazan.

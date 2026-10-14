# Migración entre versiones

Cada versión del framework publica sus **recetas de OpenRewrite** (`pe.axiz:axiz-rewrite-recipes`,
misma versión) y una guía en esta sección.

## Cómo actualizar un servicio

1. Renovate abre un PR que solo cambia el parent (`axiz-parent-service`) con la nota de la versión.
2. En ese branch:

    ```bash
    axiz upgrade --to 1.3.0 --dry-run   # muestra el diff de las recetas sin cambiar nada
    axiz upgrade --to 1.3.0             # parent, manifiesto y recetas
    ./mvnw verify
    ```

3. Revisa el diff, ajusta lo que la guía de la versión marque como manual y sube el PR.

`axiz upgrade` actualiza la versión en el `pom.xml` y en `.axiz/project.yaml` y ejecuta
`rewrite:run` con las recetas de la versión destino (`--skip-rewrite` solo cambia versiones). Las
recetas encadenan las de versiones intermedias, así se puede saltar de minor en minor.

## Política

- Se soportan la versión actual y la minor anterior.
- Lo obsoleto se marca `@Deprecated(forRemoval = true)` al menos una minor antes de borrarse, y la
  receta de esa versión hace el reemplazo.

## Guías por versión

### 0.1

Primera versión: no hay migración. Los servicios creados con versiones previas a la publicación
(prototipos) se regeneran con `axiz new` sobre su contrato y se mueve el código de negocio.

#### Cambios de la revisión (respecto del paquete preliminar 0.1.0 con R2DBC)

Si ya creaste tablas o stores propios con el paquete preliminar:

- **Inbox (JDBC y R2DBC):** la tabla tiene la columna del dueño de la concesión.

    ```sql
    ALTER TABLE axiz_inbox ADD COLUMN owner VARCHAR(64);
    ```

    Las filas existentes sin dueño se comportan como concesiones de una entrega desconocida: se
    retoman cuando vence su `lease_until`.

- **Stores propios de idempotencia o inbox:** `IdempotencyStore.complete` y `release`, y
  `InboxStore.markProcessed` y `release`, reciben el token de dueño y devuelven `boolean`. Valida
  la implementación con las comprobaciones de `axiz-core-store-contracts`
  (`IdempotencyStoreContract`, `InboxStoreContract`).
- **`IdempotencyService`:** `complete` y `fail` reciben el `Proceed` devuelto por `begin`. Las
  llaves guardadas antes del cambio usaban otro alcance: se ignoran y vencen solas.
- **`RequestMetadata`** suma el componente `issuer` (emisor del token); usa el builder o
  `withCaller(clientId, subject, tenant, issuer)` en lugar del constructor.
- **Wrapper de Maven:** verifica la distribución antes de usarla. Fija el checksum oficial una vez
  con `./mvnw --pin-checksum` y haz commit de `.mvn/wrapper/maven-wrapper.properties`; los releases lo
  exigen.

#### Cambios de la segunda revisión


- **Llave del inbox = `source` + `id`.** Las registraciones del paquete preliminar usaban solo el
  `id`, así que no coinciden con las nuevas. Un mensaje ya procesado que se vuelva a entregar
  durante la ventana de retención se procesaría otra vez. En instalaciones del paquete preliminar
  hay dos opciones:
  - parte de un inbox vacío, sin mensajes pendientes de reentrega; o
  - deja que los handlers idempotentes del negocio absorban la ventana.
- **Esquema del inbox:** si tu tabla no tiene `owner`, el servicio no arranca y el error indica la
  migración. Crea una **nueva** migración con el contenido de
  `axiz/sql/inbox-postgresql-upgrade-owner.sql` y nunca edites una que ya se aplicó.
- **Idempotencia HTTP:**
  - `IdempotencyInterceptor` desaparece: ahora la idempotencia corre como advisor después de
    `@PreAuthorize`. Si registrabas el interceptor a mano, quítalo.
  - `IdempotencyFilter` e `IdempotencyWebFilter` reciben
    `(service, maxBodySize, maxResponseSize)`.
  - `IdempotencyService.fingerprint` usa la query tal como llega, sin reordenarla. Las llaves
    guardadas con la huella anterior pueden responder 422 a un reintento dentro de su retención.
- **Stores en memoria:** `axiz.idempotency.store=memory`, `axiz.lock.store=memory` y
  `axiz.storage.type=memory` solo se aceptan con `axiz.config.environment` en `local`, `test` o
  `ci`. Los ambientes desplegados necesitan Redis, DynamoDB o S3 (ver
  [Perfil operativo](../operacion/despliegue.md#perfil-operativo)).
- **Autorización por recurso:** declara una `AccessPolicy` para cada comando o consulta que nombre
  recursos de un cliente, o marca con `@OpenAccess(reason)` los internos. Luego define
  `axiz.cqrs.authorization.unprotected: fail`. El valor por defecto `warn` lista en el log lo que
  falta cubrir. En servicios reactivos, las políticas que leen estado implementan
  `ReactiveAccessPolicy`.
- **Sagas:**
  - Existe el estado `IN_DOUBT` y el método `SagaListener.inDoubt` (con implementación por
    defecto).
  - Un paso que no implementa `verify` deja la saga en `IN_DOUBT` ante un fallo que no es un
    rechazo definitivo. Antes compensaba.
  - Lanza `SagaStepRejectedException`, o un error 4xx de negocio, cuando el participante rechazó
    con certeza.
  - Si tu tabla de sagas tiene un `CHECK` sobre `status`, añade `IN_DOUBT`.
- **Auditoría durable:** `axiz.audit.durable.enabled: true` requiere `axiz-core-outbox` y un
  destino `audit` en `axiz.messaging.destinations`.
- **Demo de pagos:**
  - `PaymentInstruction` suma `customerId` y `MovementRequest` suma `holderId`.
  - El core bancario rechaza débitos de cuentas ajenas (`CB-17`).
  - payments-business suma la migración `V3` (reservas diarias y `request_key`), y
    payments-history suma `V3__inbox_owner.sql`.
- **CI:** los workflows de librería publican en dos pasos. `lib-publish` con `stage: true` sube a
  un repositorio temporal y `lib-promote` publica cuando pasaron todos los gates. Un repositorio
  que llame a `lib-publish` directamente debe pasar a ese esquema; ver
  [CI/CD](../operacion/ci-cd.md).
- **Tokens de aplicación:** `ClaimMapping` suma el parámetro `machine` y
  `axiz.security.claims.machine` define las marcas de los tokens de client credentials. Los
  valores por defecto cubren Keycloak y Microsoft Entra. Revisa la guía de
  [seguridad](../guias/seguridad.md#resource-server) si usas otro proveedor. Un realm de Keycloak
  importado con scopes propios debe declarar el scope `basic` (claim `sub`) y el mapper de
  `preferred_username`, como `axiz-demo/local/keycloak/axiz-realm.json`.
- **Build local y gate de CI:** `./mvnw verify` ya no corre Checkstyle, Error Prone + NullAway
  ni la convergencia de dependencias; esos controles se corren con `-Pquality`, que es lo que
  ejecutan los workflows compartidos, y también la revisión de formato (Spotless). Ejecuta
  `./mvnw spotless:apply` antes de subir cambios. Spotless usa google-java-format 1.30, la primera
  versión que corre sobre JDK 25, y dejó de ordenar los POM (sortpom).
  `-Daxiz.errorprone.skip` ya no existe.

<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-commons

Contratos operativos neutrales respecto del framework: límites y resultados de transacciones, ambientes de despliegue, políticas de seguridad del transporte y marcas de código generado y de estado revisado.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-commons</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `Environments` | Perfil operativo de un despliegue (`axiz.config.environment`). |
| `Generated` | Marca una clase escrita por un generador y no por una persona (los modelos que openapi-generator produce desde el contrato de un servicio); JaCoCo deja esas clases fuera de los umbrales de cobertura. |
| `TransactionRunner` | Ejecuta trabajo dentro de una transacción (en Spring, `TransactionTemplate`). |
| `TransactionOutcome` | Evidencia sobre una transacción local; un rollback no prueba la ausencia de efectos externos. |
| `ReviewedState` | Registra la revisión de un estado mutable compartido; no prueba inmutabilidad ni seguridad entre hilos. |
| `TransportSecurityPolicy` | Límite de transporte inmutable; HTTP sin TLS requiere una excepción explícita en local, test o ci. |
| `DataTransportSecurityPolicy` | Política de TLS y verificación del par para almacenes de datos, independiente de la tecnología del cliente. |

Tipos relacionados: `AsyncLoadingCache`, `BoundedCallbackExecutor`, `Stages` están en `axiz-core-concurrent`; `ApiResponse`, `HeaderCatalog`, `HeaderDefinition`, `Page`, `PageMetadata`, `PageRequest`, `PageResponse`, `Sort`, `StandardHeaders` están en `axiz-core-api`; `RequestContext`, `RequestMetadata` están en `axiz-core-context`; `JsonReader`, `JsonWriter` están en `axiz-core-json`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-commons verify`.

## JDBC

La implementación JDBC y los recursos SQL están en `axiz-kit-jdbc`; este módulo contiene contratos y
orquestación neutrales respecto del framework, y `TransactionRunner` está en `pe.axiz.core.commons.tx`. Un servicio agrega el
adaptador JDBC de una capacidad con `axiz add` (o con el Initializr); ver
[límites JDBC](../../arquitectura/jdbc.md).

## Utilidades del lenguaje

`Checks`, `Strings`, `Dates` y `Result` están en
[`axiz-core-lang`](axiz-core-lang.md), paquete `pe.axiz.core.lang`. Un dominio que solo
necesita estas utilidades depende directamente de ese módulo, que solo usa el JDK.

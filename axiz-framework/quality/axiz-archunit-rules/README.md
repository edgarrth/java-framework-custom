<!-- generated -->
# axiz-archunit-rules

Reglas de arquitectura para los servicios de axiz: higiene general y un conjunto de reglas por estilo de arquitectura.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-archunit-rules</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizArchitecture` | Punto de entrada para las pruebas de los servicios. |
| `BatchRules` | Estilo batch: readers, processors y writers en `job`; sin controladores HTTP. |
| `BffRules` | Estilo BFF: sin persistencia propia ni modelo de dominio; compone APIs de servicios posteriores. |
| `CqrsRules` | CQRS sobre hexagonal: los comandos y las consultas son records inmutables; los handlers de consultas nunca dependen de los handlers de comandos ni de los puertos de escritura. |
| `EdaRules` | Estilo orientado a eventos: los handlers están en `handler` y nunca exponen controladores HTTP. |
| `GeneralRules` | Reglas de higiene que cumplen todos los servicios y módulos de axiz. |
| `HexagonalRules` | Estilo hexagonal: `domain` en el centro, `application` (casos de uso y puertos) alrededor, `adapter.in` y `adapter.out` en el exterior, y `config` cableando todo. |
| `LayeredRules` | Estilo layered: `controller -> service -> repository`, con las entidades de persistencia en los repositorios. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl quality/axiz-archunit-rules verify`.

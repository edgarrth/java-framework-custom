<!-- generated -->
# axiz-structure-hexagonal-spring-boot-starter

Estilo hexagonal: casos de uso, puertos y adaptadores, transacción por caso de uso y eventos de dominio al confirmar.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-hexagonal-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ApplicationEventDomainEventSink` | Publicación en proceso después del commit mediante eventos de aplicación de Spring; se usa cuando el servicio no tiene outbox (los eventos quedan dentro del servicio). |
| `AxizHexagonalAutoConfiguration` | Estilo hexagonal: las clases `@UseCase` se ejecutan en una transacción con una observación y publican sus eventos de dominio al confirmar, mediante el outbox cuando el servicio tiene uno. |
| `AxizHexagonalProperties` | Configuración del estilo hexagonal (`axiz.hexagonal.*`). |
| `OutboxDomainEventSink` | Escribe los eventos de dominio en el outbox dentro de la transacción del caso de uso, de modo que se publican si y solo si el cambio de negocio hace commit. |
| `SpringUseCaseTransaction` | Transacciones de caso de uso con el gestor de transacciones de Spring (se une a una transacción existente). |
| `UseCaseInterceptor` | Envuelve los métodos públicos de las clases `UseCase`: observación `axiz.usecase` con la operación y el resultado, una transacción y el ciclo de vida de los eventos de dominio. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/hexagonal/axiz-structure-hexagonal-spring-boot-starter verify`.

## Contrato del resultado reactivo

`ReactiveUseCase` emite cero o un resultado, difiere el trabajo hasta la suscripción y propaga la
cancelación. Devolver el tipo covariante `Flux` no cambia ese contrato: varios valores o un error
tardío del publicador hacen fallar el caso de uso y revierten su transacción. Este límite corresponde
a una sola operación de aplicación; emitir varios resultados independientes en streaming corresponde
a una API separada de consulta o de streaming con semántica transaccional explícita.

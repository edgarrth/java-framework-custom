# CQRS

Se monta sobre [hexagonal](../estructuras/hexagonal.md) (mismos paquetes y reglas) y separa el lado de escritura
del de lectura: cada operación es un `Command` o una `Query` con un único handler, y ambos viajan
por un bus con un pipeline ordenado.

```text
application/
├── command/   <Operacion>Command (record) + <Operacion>Handler
└── query/     <Operacion>Query (record) + <Operacion>Handler
```

```java
public record CreateOrderCommand(CreateOrderRequest body) implements Command<Order> {}

@Component
public class CreateOrderHandler implements CommandHandler<CreateOrderCommand, Order> {
  @Override
  public Order handle(CreateOrderCommand command) { ... }
}

// en el controlador
Order result = commandBus.send(new CreateOrderCommand(body));
OrderPage page = queryBus.ask(new ListOrdersQuery(status, page));
```

## Pipeline

Cada request atraviesa, en orden:

| Paso | Qué hace |
|---|---|
| Observación | span `axiz.command` o `axiz.query` alrededor de todo |
| Validación | Bean Validation del command o query (400 con una violación por campo) |
| Autorización | `@RequiresAuthority("SCOPE_payments:write")` en el tipo (403 si falta) |
| Idempotencia | `IdempotentCommand`: el primer resultado se guarda y se devuelve igual para la misma llave y el mismo command; la misma llave con otro command es 422 |
| Transacción | commands en transacción de escritura, queries en solo lectura |
| Auditoría | `AuditedCommand`: acción, recurso y resultado (sanitizados) |
| Métricas | cuenta y mide cada request como evento genérico con el nombre de la operación; los que implementan `MetricSource` aportan montos y tags |

Se agregan pasos propios con un bean `PipelineBehavior` (con su `order()`).

**Reglas** (`CqrsRules`): las de hexagonal, más commands y queries como records inmutables y
ningún `QueryHandler` dependiendo de un `CommandHandler` (el lado de lectura nunca escribe).

## CQRS reactivo

En el stack reactivo los handlers devuelven un `Mono` y los buses son `ReactiveCommandBus` y
`ReactiveQueryBus`. El pipeline es el mismo, armado con operadores que no bloquean: el span es el
padre de todo lo que ocurre río abajo (sentencias R2DBC, llamadas HTTP) a través del Reactor
Context, la autorización lee el `ReactiveSecurityContextHolder`, la transacción es reactiva
(`R2dbcTransactionManager`, de solo lectura para queries) y los colaboradores bloqueantes
(almacén de idempotencia, publicador de auditoría) corren en `boundedElastic`.

```java
@Component
public class GetPaymentHandler implements ReactiveQueryHandler<GetPaymentQuery, PaymentHistory> {
  @Override
  public Mono<PaymentHistory> handle(GetPaymentQuery query) {
    return histories.find(query.paymentId())
        .switchIfEmpty(Mono.error(() -> new NotFoundException("payment", query.paymentId())));
  }
}

// en el controlador WebFlux
return queryBus.ask(new GetPaymentQuery(id)).map(p -> ResponseEntity.ok(toApi(p)));
```

Los pasos propios se agregan con un bean `ReactivePipelineBehavior`. Un tipo de request con un
handler bloqueante y uno reactivo es un error al arrancar. El generador crea handlers reactivos en
el stack reactivo, salvo con DynamoDB (cliente bloqueante): ahí los handlers son los de siempre y el
controlador los ejecuta en `boundedElastic`.

## Proyecciones

`Projection<E>` mantiene un modelo de lectura a partir de eventos: eventos de dominio en el mismo
proceso, o eventos de integración que llegan por el estilo [EDA](../estructuras/eda.md). Un handler que falta para
un tipo de request es un error de configuración que se detecta al arrancar.

En el demo, `payments-business` usa CQRS con Aurora: órdenes de pago con calendario de negocio,
corte horario, secuencias, cifrado de campos con blind index y outbox; `payments-history` usa CQRS
reactivo con Aurora por R2DBC para el historial de pagos de cada cliente.

# BFF

Para servicios de canal (web, móvil, partners) que agregan otros servicios **sin dominio ni
persistencia propios**: componen llamadas en paralelo, toleran partes opcionales, adaptan la
respuesta al canal y cachean lecturas.

**Reglas** (`BffRules`): sin JPA, Spring Data JPA ni DynamoDB Enhanced Client.

## Composición

=== "Reactivo"

    ```java
    return ReactiveComposer.within(timeout)
        .required("order", Mono.just(order))
        .optional("alias", aliasOf(customer, order), () -> null)   // si falla o vence: null
        .run()
        .map(c -> ChannelViews.payment(c.get("order", PaymentOrder.class), c.get("alias", String.class)));
    ```

=== "Servlet"

    ```java
    Composition c = compositions.compose(Duration.ofSeconds(2))
        .required("account", () -> accounts.get(id))
        .optional("offers", () -> offers.forAccount(id), List::of)
        .run();
    ```

Las partes corren en paralelo (virtual threads en servlet, suscripciones en reactivo) y llevan el
contexto del llamador (metadata de la request, tenant, MDC y traza). Una parte requerida que falla
hace fallar la composición; una opcional cae a su valor por defecto ante error o timeout.
`ChannelAdapters` elige el `ChannelAdapter` del canal del llamador (con uno por defecto `*`).

## Lo que suele acompañar a un BFF

- **Token relay** hacia los servicios de dominio (`auth.type: TOKEN_RELAY` del cliente HTTP).
- **Caché de lectura** con `@AxizCacheable` (sobre `Mono`/`Flux` en reactivo).
- **Idempotencia de canal** con `@Idempotent` y el header `Idempotency-Key`.
- **Feature flags** por canal, tenant o porcentaje.
- **Enmascarado** de datos sensibles en las vistas del canal.
- **Traducción de errores** de los servicios internos a errores del canal.

En el demo, `payments-experience` (reactivo) hace todo esto sobre `payments-business` y
`beneficiaries-crud`.

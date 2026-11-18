<!-- generated -->
# axiz-idempotency-spring-boot-autoconfigure

@Idempotent endpoints with Idempotency-Key, stored responses in Redis or DynamoDB (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-idempotency-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizIdempotencyAutoConfiguration` | `@Idempotent` operations with the `Idempotency-Key` header: responses stored in ElastiCache (Redis) or DynamoDB and replayed; 400, 409 and 422 on misuse. |
| `AxizIdempotencyDynamoDbAutoConfiguration` | DynamoDB idempotency store with conditional writes and TTL. |
| `AxizIdempotencyProperties` | API idempotency (`axiz.idempotency.*`) for operations annotated with `@Idempotent`. |
| `AxizIdempotencyRedisAutoConfiguration` | ElastiCache (Redis/Valkey) idempotency store on Boot's Lettuce connection settings. |
| `IdempotencyFilter` | Buffers the body of unsafe requests carrying `Idempotency-Key` (up to `maxBodySize`) so it can be fingerprinted (method, path, query string as sent and body), and stores the response once the handler finished. |
| `IdempotencyMethodInterceptor` | Applies `@Idempotent` around the handler method, **inside** method security: it is ordered after Spring Security's `@PreAuthorize`, `@Secured` and JSR-250 interceptors, so a replay is only returned when the caller is still authorized for this request, with the resolved arguments (path variables, body) the policies need. |
| `IdempotencyReplayHandler` | Writes a stored response for an `IdempotentReplay` (servlet and reactive stacks): stored status, replayed headers and the original bytes, plus `Idempotent-Replayed: true`. |
| `IdempotencyWebFilter` | Reactive counterpart of `IdempotencyFilter`: for unsafe requests it fingerprints the buffered body (method, path, query string as sent and body) and hands it to the advice through the Reactor context; the advice decides after method authorization. |
| `IdempotentReplay` | Control-flow signal: the request repeats a completed one and its stored response must be returned. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-idempotency-spring-boot-autoconfigure verify`.

## Technical payload protection

`axiz.idempotency.response-protection=envelope` opts into authenticated payload encryption;
`none` is the default. Envelope mode requires the matching synchronous/native
`EnvelopeEncryptor` and fails startup when it is absent. Protected records cannot
be read in `none` mode; migration and retained read keys must be planned before
changing the mode. See [technical storage protection](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).

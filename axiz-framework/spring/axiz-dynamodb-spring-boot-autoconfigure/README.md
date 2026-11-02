<!-- generated -->
# axiz-dynamodb-spring-boot-autoconfigure

DynamoDB Enhanced Client, table naming per environment and generic repositories (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-dynamodb-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizDynamoDbAutoConfiguration` | One DynamoDB client per application (observed, default credentials chain, optional region and LocalStack endpoint from `axiz.aws.*`), the enhanced client and the repository factory. |
| `DynamoRepositories` | Creates repositories on the single enhanced client, applying the table name prefix. |
| `ReactiveDynamoRepositories` | Creates native repositories and deferred Reactor facades for WebFlux applications. |
| `ReactiveDynamoRepository` | Mono API with empty reads, one-page queries, native cancellation and unchanged SDK errors. |

Servlet applications receive synchronous clients and repositories. Reactive applications receive
only `DynamoDbAsyncClient` and `ReactiveDynamoRepositories`; the framework does not create a
synchronous fallback. A supplied asynchronous client avoids default credential resources. A
custom reactive factory backs off all framework-owned resources. The owned client initializes
credentials, TLS and DNS support at startup using `axiz.aws.*`; repository methods submit I/O
on subscription and preserve cancellation through the Enhanced Client's mapping stages.

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

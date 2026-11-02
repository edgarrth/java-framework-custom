package pe.axiz.spring.dynamodb;

import java.util.Objects;
import pe.axiz.kit.dynamodb.DynamoAsyncRepository;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/** Creates native repositories and deferred Reactor facades using one borrowed transport. */
public final class ReactiveDynamoRepositories {
  private final DynamoDbAsyncClient client;
  private final String tablePrefix;

  public ReactiveDynamoRepositories(DynamoDbAsyncClient client, String tablePrefix) {
    this.client = Objects.requireNonNull(client);
    this.tablePrefix = Objects.requireNonNull(tablePrefix);
  }

  public <T> ReactiveDynamoRepository<T> create(String table, TableSchema<T> schema) {
    return new ReactiveDynamoRepository<>(
        new DynamoAsyncRepository<>(client, tableName(table), schema));
  }

  public <T> ReactiveDynamoRepository<T> create(String table, Class<T> type) {
    return create(
        table, type.isRecord() ? TableSchema.fromImmutableClass(type) : TableSchema.fromBean(type));
  }

  public String tableName(String table) {
    return tablePrefix + Objects.requireNonNull(table);
  }
}

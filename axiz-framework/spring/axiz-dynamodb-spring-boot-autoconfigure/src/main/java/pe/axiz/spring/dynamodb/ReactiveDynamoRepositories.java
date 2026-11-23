package pe.axiz.spring.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.function.Supplier;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.kit.dynamodb.DynamoAsyncRepository;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Crea repositorios nativos y fachadas diferidas de Reactor usando un único transporte prestado.
 */
public final class ReactiveDynamoRepositories {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final DynamoDbAsyncClient client;

  private final String tablePrefix;
  private final Supplier<CursorCodec> cursors;

  public ReactiveDynamoRepositories(DynamoDbAsyncClient client, String tablePrefix) {
    this(
        client,
        tablePrefix,
        () -> {
          throw new IllegalStateException(
              "Configure axiz.dynamodb.cursor.current-key-id and shared keys from secrets before"
                  + " using pagination");
        });
  }

  public ReactiveDynamoRepositories(
      DynamoDbAsyncClient client, String tablePrefix, Supplier<CursorCodec> cursors) {
    this.cursors = Objects.requireNonNull(cursors);
    this.client = Objects.requireNonNull(client);
    this.tablePrefix = Objects.requireNonNull(tablePrefix);
  }

  public <T> ReactiveDynamoRepository<T> create(String table, TableSchema<T> schema) {
    return new ReactiveDynamoRepository<>(
        new DynamoAsyncRepository<>(client, tableName(table), schema, cursors));
  }

  public <T> ReactiveDynamoRepository<T> create(String table, Class<T> type) {
    return create(
        table, type.isRecord() ? TableSchema.fromImmutableClass(type) : TableSchema.fromBean(type));
  }

  public CursorCodec cursors() {
    return cursors.get();
  }

  public String tableName(String table) {
    return tablePrefix + Objects.requireNonNull(table);
  }
}

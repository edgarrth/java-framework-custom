package pe.axiz.spring.dynamodb;

import java.util.Objects;
import java.util.function.Supplier;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.kit.dynamodb.DynamoRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/** Crea repositorios sobre el único cliente mejorado, aplicando el prefijo de nombre de tabla. */
public final class DynamoRepositories {

  private final DynamoDbEnhancedClient client;
  private final String tablePrefix;
  private final Supplier<CursorCodec> cursors;

  public DynamoRepositories(DynamoDbEnhancedClient client, String tablePrefix) {
    this(
        client,
        tablePrefix,
        () -> {
          throw new IllegalStateException(
              "Configure axiz.dynamodb.cursor.current-key-id and shared keys from secrets before"
                  + " using pagination");
        });
  }

  public DynamoRepositories(
      DynamoDbEnhancedClient client, String tablePrefix, Supplier<CursorCodec> cursors) {
    this.cursors = Objects.requireNonNull(cursors);
    this.client = Objects.requireNonNull(client);
    this.tablePrefix = Objects.requireNonNull(tablePrefix);
  }

  public <T> DynamoRepository<T> create(String table, TableSchema<T> schema) {
    return new DynamoRepository<>(client, tableName(table), schema, cursors);
  }

  /** Repositorio para un bean o una clase inmutable anotada para el cliente mejorado. */
  public <T> DynamoRepository<T> create(String table, Class<T> type) {
    TableSchema<T> schema =
        type.isRecord() ? TableSchema.fromImmutableClass(type) : TableSchema.fromBean(type);
    return create(table, schema);
  }

  public CursorCodec cursors() {
    return cursors.get();
  }

  public String tableName(String table) {
    return tablePrefix + table;
  }
}

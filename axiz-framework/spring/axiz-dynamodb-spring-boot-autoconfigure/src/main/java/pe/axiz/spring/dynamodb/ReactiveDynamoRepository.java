package pe.axiz.spring.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.kit.dynamodb.DynamoAsyncRepository;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;

/** Cold, cancellable Reactor facade. An absent item completes empty; failures remain failures. */
public final class ReactiveDynamoRepository<T> {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoAsyncRepository<T> is a borrowed collaborator; sharing its identity "
              + "preserves lifecycle, transactions or instrumentation. The field is not an owned "
              + "mutable value.")
  private final DynamoAsyncRepository<T> repository;

  public ReactiveDynamoRepository(DynamoAsyncRepository<T> repository) {
    this.repository = Objects.requireNonNull(repository);
  }

  public DynamoDbAsyncTable<T> table() {
    return repository.table();
  }

  public Mono<T> findById(String partitionKey) {
    return ReactiveStages.from(() -> repository.findById(partitionKey)).flatMap(Mono::justOrEmpty);
  }

  public Mono<T> findById(String partitionKey, String sortKey) {
    return ReactiveStages.from(() -> repository.findById(partitionKey, sortKey))
        .flatMap(Mono::justOrEmpty);
  }

  public Mono<Void> save(T item) {
    return ReactiveStages.from(() -> repository.save(item));
  }

  public Mono<Void> insert(T item, String partitionKeyAttribute) {
    return ReactiveStages.from(() -> repository.insert(item, partitionKeyAttribute));
  }

  public Mono<T> update(T item) {
    return ReactiveStages.from(() -> repository.update(item));
  }

  public Mono<Void> delete(String partitionKey) {
    return ReactiveStages.from(() -> repository.delete(partitionKey));
  }

  public Mono<Void> delete(String partitionKey, String sortKey) {
    return ReactiveStages.from(() -> repository.delete(partitionKey, sortKey));
  }

  public Mono<Page<T>> query(String partitionKey, int limit, @Nullable String cursor) {
    return ReactiveStages.from(() -> repository.query(partitionKey, limit, cursor));
  }

  public Mono<Page<T>> queryBeginsWith(
      String partitionKey, String sortKeyPrefix, int limit, @Nullable String cursor) {
    return ReactiveStages.from(
        () -> repository.queryBeginsWith(partitionKey, sortKeyPrefix, limit, cursor));
  }

  public Mono<Page<T>> queryIndex(
      String indexName, String partitionKey, int limit, @Nullable String cursor) {
    return ReactiveStages.from(() -> repository.queryIndex(indexName, partitionKey, limit, cursor));
  }

  public Mono<Void> transact(Consumer<TransactWriteItemsEnhancedRequest.Builder> builder) {
    return ReactiveStages.from(() -> repository.transact(builder));
  }
}

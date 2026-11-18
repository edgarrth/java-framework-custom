package pe.axiz.adapter.tokenization.dynamodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.vault.AsyncTokenVault;
import pe.axiz.core.tokenization.vault.DeleteOutcome;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.PutOutcome;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultScope;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Native SDK futures with the same transaction plans and serializable lookup as the sync port. */
public final class DynamoDbAsyncTokenVault implements AsyncTokenVault {
  private final DynamoDbAsyncClient client;
  private final VaultRequests requests;

  public DynamoDbAsyncTokenVault(
      DynamoDbAsyncClient client, String table, Duration requestTimeout) {
    this.client = Objects.requireNonNull(client);
    requests = new VaultRequests(table, requestTimeout);
  }

  @Override
  public CompletionStage<FingerprintKeyState> keyState(String keysetId) {
    return read(
        () ->
            Stages.map(
                client.getItem(requests.get(VaultItems.controlKey(keysetId))),
                answer -> VaultItems.control(keysetId, answer.item())));
  }

  @Override
  public CompletionStage<Optional<VaultRecord>> find(VaultScope scope, Token token) {
    return read(
        () ->
            Stages.map(
                client.getItem(requests.get(VaultItems.tokenKey(token))),
                answer -> {
                  if (answer.item().isEmpty()) return Optional.empty();
                  VaultRecord record = VaultItems.record(answer.item());
                  if (!record.token().equals(token)) throw new VaultIntegrityException();
                  return record.scope().equals(scope) ? Optional.of(record) : Optional.empty();
                }));
  }

  @Override
  public CompletionStage<Optional<VaultRecord>> findByFingerprint(
      VaultScope scope, List<KeyedHash> candidates) {
    List<KeyedHash> snapshot = List.copyOf(candidates);
    return read(() -> lookup(scope, snapshot, 0));
  }

  private CompletionStage<Optional<VaultRecord>> lookup(
      VaultScope scope, List<KeyedHash> candidates, int attempt) {
    if (attempt >= VaultRequests.ATTEMPTS)
      return CompletableFuture.failedFuture(VaultRequests.unavailable());
    return Stages.compose(
        client.transactGetItems(requests.references(scope, candidates, null)),
        initial -> {
          Optional<Token> owner = requests.owner(scope, candidates, initial.responses(), false);
          if (owner.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
          return Stages.compose(
              client.transactGetItems(requests.references(scope, candidates, owner.get())),
              snapshot -> {
                Optional<Token> confirmed =
                    requests.owner(scope, candidates, snapshot.responses(), true);
                if (confirmed.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
                if (!owner.equals(confirmed)) return lookup(scope, candidates, attempt + 1);
                return CompletableFuture.completedFuture(
                    Optional.of(
                        requests.association(
                            scope, candidates, owner.get(), snapshot.responses())));
              });
        });
  }

  @Override
  public CompletionStage<PutOutcome> putIfAbsent(
      VaultRecord record, FingerprintKeyState state, List<KeyedHash> others) {
    List<KeyedHash> snapshot = List.copyOf(others);
    return Stages.call(() -> put(record, snapshot, requests.insert(record, state, snapshot), 0));
  }

  private CompletionStage<PutOutcome> put(
      VaultRecord record, List<KeyedHash> others, TransactWriteItemsRequest request, int attempt) {
    if (attempt >= VaultRequests.ATTEMPTS)
      return CompletableFuture.failedFuture(VaultRequests.unavailable());
    return transaction(
        () -> client.transactWriteItems(request),
        ignored -> CompletableFuture.completedFuture(new PutOutcome.Created()),
        rejected -> {
          var reasons = VaultRequests.reasons(rejected, request.transactItems().size());
          if (VaultRequests.condition(reasons, 0)
              || VaultRequests.condition(reasons, reasons.size() - 1))
            return CompletableFuture.completedFuture(new PutOutcome.StaleKeyState());
          boolean fingerprint = false;
          for (int i = 2; i < reasons.size() - 1; i++)
            fingerprint |= VaultRequests.condition(reasons, i);
          if (fingerprint) {
            var candidates = new ArrayList<>(record.fingerprints());
            candidates.addAll(others);
            return Stages.compose(
                findByFingerprint(record.scope(), candidates),
                winner ->
                    winner.isPresent()
                        ? CompletableFuture.completedFuture(
                            new PutOutcome.FingerprintExists(winner.get().token()))
                        : put(record, others, request, attempt + 1));
          }
          if (VaultRequests.condition(reasons, 1))
            return CompletableFuture.completedFuture(new PutOutcome.TokenCollision());
          if (VaultRequests.conflict(reasons)) return put(record, others, request, attempt + 1);
          return CompletableFuture.failedFuture(VaultRequests.unavailable());
        });
  }

  @Override
  public CompletionStage<DeleteOutcome> delete(VaultRecord expected) {
    return Stages.call(() -> remove(expected, requests.delete(expected), 0));
  }

  private CompletionStage<DeleteOutcome> remove(
      VaultRecord record, TransactWriteItemsRequest request, int attempt) {
    if (attempt >= VaultRequests.ATTEMPTS)
      return CompletableFuture.failedFuture(VaultRequests.unavailable());
    return transaction(
        () -> client.transactWriteItems(request),
        ignored -> CompletableFuture.completedFuture(DeleteOutcome.DELETED),
        rejected -> {
          var reasons = VaultRequests.reasons(rejected, request.transactItems().size());
          if (VaultRequests.condition(reasons, 0))
            return Stages.map(
                find(record.scope(), record.token()),
                found -> found.isEmpty() ? DeleteOutcome.NOT_FOUND : DeleteOutcome.CHANGED);
          for (int i = 1; i < reasons.size(); i++)
            if (VaultRequests.condition(reasons, i))
              return CompletableFuture.completedFuture(DeleteOutcome.INTEGRITY_FAILURE);
          if (VaultRequests.conflict(reasons)) return remove(record, request, attempt + 1);
          return CompletableFuture.failedFuture(VaultRequests.unavailable());
        });
  }

  private static <T> CompletionStage<T> read(Supplier<CompletionStage<T>> action) {
    return Stages.recover(
        Stages.call(action),
        failure -> {
          throw VaultRequests.readFailure(failure);
        });
  }

  private static <V, T> CompletionStage<T> transaction(
      Supplier<CompletionStage<V>> action,
      Function<V, CompletionStage<T>> success,
      Function<TransactionCanceledException, CompletionStage<T>> rejected) {
    // A canceled public future must not misclassify a write that the SDK already accepted.
    // Callers keep their lease until these bounded native calls settle.
    return Stages.compose(
        Stages.recover(
            Stages.map(Stages.call(action), answer -> new WriteAnswer<>(answer, null)),
            error -> new WriteAnswer<V>(null, error)),
        answer -> {
          Throwable failure = answer.failure();
          if (failure == null) return success.apply(Objects.requireNonNull(answer.value()));
          if (failure instanceof TransactionCanceledException conditional)
            return rejected.apply(conditional);
          return CompletableFuture.failedFuture(VaultRequests.writeFailure(failure));
        });
  }

  private record WriteAnswer<V>(@Nullable V value, @Nullable Throwable failure) {}
}

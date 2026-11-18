package pe.axiz.spring.tokenization.vault.dynamodb;

import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultReadiness;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultReadiness.Keys;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties.TypePolicy;

/** Startup-only coordination; never invoked on a request or reactive event loop. */
final class DynamoVaultStartup {
  private DynamoVaultStartup() {}

  static DynamoDbVaultReadiness readiness(
      AxizTokenizationDynamoDbProperties storage,
      AxizTokenizationVaultProperties vault,
      BlindIndex indexes,
      String wrappingWrite,
      Set<String> wrappingRead) {
    var keysets =
        vault.types().values().stream().map(TypePolicy::keyset).collect(Collectors.toSet());
    return new DynamoDbVaultReadiness(
        storage.table(),
        storage.requestTimeout(),
        new Keys(keysets, indexes.keyIds(), wrappingWrite, wrappingRead));
  }

  static void await(
      CompletionStage<Void> verification, AxizTokenizationDynamoDbProperties storage) {
    // Two metadata reads plus at most two transactions for 64 keysets. SDK bounds every call.
    long millis = storage.requestTimeout().multipliedBy(4).plusSeconds(1).toMillis();
    try {
      verification.toCompletableFuture().get(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | TimeoutException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Token vault startup verification did not complete");
  }
}

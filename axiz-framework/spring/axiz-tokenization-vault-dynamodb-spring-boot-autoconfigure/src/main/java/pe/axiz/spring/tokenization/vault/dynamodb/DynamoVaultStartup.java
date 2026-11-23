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

/**
 * Coordinación exclusiva del arranque; nunca se invoca en una solicitud ni en un event loop
 * reactivo.
 */
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
    // Dos lecturas de metadatos más como máximo dos transacciones para 64 keysets. El SDK acota
    // cada llamada.
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

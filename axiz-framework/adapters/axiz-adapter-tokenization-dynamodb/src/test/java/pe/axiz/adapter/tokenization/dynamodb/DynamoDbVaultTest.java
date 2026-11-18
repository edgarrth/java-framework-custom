package pe.axiz.adapter.tokenization.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.SCOPE;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.STATE;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.TOKEN;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.hashes;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.item;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.record;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.vault.DeleteOutcome;
import pe.axiz.core.tokenization.vault.PutOutcome;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultUncertainException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class DynamoDbVaultTest {
  private final DynamoDbClient client = mock(DynamoDbClient.class);
  private final DynamoDbAsyncClient async = mock(DynamoDbAsyncClient.class);
  private final DynamoDbTokenVault vault =
      new DynamoDbTokenVault(client, "token-vault", Duration.ofSeconds(2));
  private final DynamoDbAsyncTokenVault nativeVault =
      new DynamoDbAsyncTokenVault(async, "token-vault", Duration.ofSeconds(2));

  static TransactionCanceledException cancelled(String... reasons) {
    return TransactionCanceledException.builder()
        .cancellationReasons(
            Arrays.stream(reasons)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList())
        .build();
  }

  @Test
  void confirmedConditionalOutcomesAreDistinctInBothExecutionModels() {
    var old = List.of(hashes(SCOPE).getLast());
    when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(cancelled("ConditionalCheckFailed", "None", "None", "None", "None"))
        .thenThrow(cancelled("None", "ConditionalCheckFailed", "None", "None", "None"))
        .thenReturn(TransactWriteItemsResponse.builder().build());
    assertInstanceOf(PutOutcome.StaleKeyState.class, vault.putIfAbsent(record(), STATE, old));
    assertInstanceOf(PutOutcome.TokenCollision.class, vault.putIfAbsent(record(), STATE, old));
    assertInstanceOf(PutOutcome.Created.class, vault.putIfAbsent(record(), STATE, old));
    when(async.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenReturn(
            CompletableFuture.failedFuture(
                cancelled("ConditionalCheckFailed", "None", "None", "None", "None")))
        .thenReturn(
            CompletableFuture.failedFuture(
                cancelled("None", "ConditionalCheckFailed", "None", "None", "None")))
        .thenReturn(
            CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
    assertInstanceOf(
        PutOutcome.StaleKeyState.class,
        nativeVault.putIfAbsent(record(), STATE, old).toCompletableFuture().join());
    assertInstanceOf(
        PutOutcome.TokenCollision.class,
        nativeVault.putIfAbsent(record(), STATE, old).toCompletableFuture().join());
    assertInstanceOf(
        PutOutcome.Created.class,
        nativeVault.putIfAbsent(record(), STATE, old).toCompletableFuture().join());
  }

  @Test
  void lostAcknowledgementIsNeverRetriedAsConditionalConflict() {
    var old = List.of(hashes(SCOPE).getLast());
    var lost = SdkClientException.create("synthetic transport loss");
    when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(lost);
    assertThrows(VaultUncertainException.class, () -> vault.putIfAbsent(record(), STATE, old));
    verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    when(async.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(lost));
    assertInstanceOf(
        VaultUncertainException.class,
        assertThrows(
                CompletionException.class,
                () -> nativeVault.putIfAbsent(record(), STATE, old).toCompletableFuture().join())
            .getCause());
    verify(async, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
  }

  @Test
  void confirmedContentionHasBoundedRetries() {
    when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(cancelled("None", "TransactionConflict", "None", "None", "None"));
    assertThrows(
        AxizException.class,
        () -> vault.putIfAbsent(record(), STATE, List.of(hashes(SCOPE).getLast())));
    verify(client, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));
    when(async.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenReturn(
            CompletableFuture.failedFuture(
                cancelled("None", "TransactionConflict", "None", "None", "None")));
    assertThrows(
        CompletionException.class,
        () ->
            nativeVault
                .putIfAbsent(record(), STATE, List.of(hashes(SCOPE).getLast()))
                .toCompletableFuture()
                .join());
    verify(async, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));
  }

  @Test
  void deletedIndexAndTrueOrphanAreDistinguishedByTransactionalReread() {
    var candidate = hashes(SCOPE);
    var first =
        TransactGetItemsResponse.builder()
            .responses(item(VaultItems.fingerprint(record(), candidate.getFirst())), item(Map.of()))
            .build();
    var deleted =
        TransactGetItemsResponse.builder()
            .responses(item(Map.of()), item(Map.of()), item(Map.of()))
            .build();
    var orphan =
        TransactGetItemsResponse.builder()
            .responses(
                item(VaultItems.fingerprint(record(), candidate.getFirst())),
                item(Map.of()),
                item(Map.of()))
            .build();
    when(client.transactGetItems(any(TransactGetItemsRequest.class)))
        .thenReturn(first, deleted, first, orphan);
    assertTrue(vault.findByFingerprint(SCOPE, candidate).isEmpty());
    assertThrows(VaultIntegrityException.class, () -> vault.findByFingerprint(SCOPE, candidate));
    when(async.transactGetItems(any(TransactGetItemsRequest.class)))
        .thenReturn(
            CompletableFuture.completedFuture(first),
            CompletableFuture.completedFuture(deleted),
            CompletableFuture.completedFuture(first),
            CompletableFuture.completedFuture(orphan));
    assertTrue(
        nativeVault.findByFingerprint(SCOPE, candidate).toCompletableFuture().join().isEmpty());
    assertInstanceOf(
        VaultIntegrityException.class,
        assertThrows(
                CompletionException.class,
                () -> nativeVault.findByFingerprint(SCOPE, candidate).toCompletableFuture().join())
            .getCause());
  }

  @Test
  void deletionRequiresUnchangedRecordAndReferences() {
    when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(cancelled("ConditionalCheckFailed", "None"))
        .thenThrow(cancelled("ConditionalCheckFailed", "None"))
        .thenThrow(cancelled("None", "ConditionalCheckFailed"));
    when(client.getItem(any(GetItemRequest.class)))
        .thenReturn(
            GetItemResponse.builder().item(VaultItems.record(record())).build(),
            GetItemResponse.builder().build());
    assertEquals(DeleteOutcome.CHANGED, vault.delete(record()));
    assertEquals(DeleteOutcome.NOT_FOUND, vault.delete(record()));
    assertEquals(DeleteOutcome.INTEGRITY_FAILURE, vault.delete(record()));
    when(async.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(cancelled("None", "ConditionalCheckFailed")));
    assertEquals(
        DeleteOutcome.INTEGRITY_FAILURE, nativeVault.delete(record()).toCompletableFuture().join());
  }

  @Test
  void readsDoNotTreatInfrastructureFailureAsAbsence() {
    when(client.getItem(any(GetItemRequest.class)))
        .thenThrow(SdkClientException.create("synthetic private provider error"));
    var failure = assertThrows(AxizException.class, () -> vault.find(SCOPE, TOKEN));
    assertEquals("AXZ-TOKEN-0503", failure.code().value());
    assertTrue(failure.getCause() == null);
    when(async.getItem(any(GetItemRequest.class)))
        .thenReturn(
            CompletableFuture.failedFuture(
                SdkClientException.create("synthetic private provider error")));
    assertInstanceOf(
        AxizException.class,
        assertThrows(
                CompletionException.class,
                () -> nativeVault.find(SCOPE, TOKEN).toCompletableFuture().join())
            .getCause());
  }
}

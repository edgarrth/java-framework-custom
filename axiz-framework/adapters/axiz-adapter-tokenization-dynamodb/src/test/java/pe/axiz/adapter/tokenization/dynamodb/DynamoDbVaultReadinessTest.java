package pe.axiz.adapter.tokenization.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultReadiness.Keys;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.ItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse;

class DynamoDbVaultReadinessTest {
  private static final Duration TIMEOUT = Duration.ofMillis(100);
  private static final Keys KEYS =
      new Keys(Set.of("identity"), Set.of("i1", "i2"), "e2", Set.of("e1", "e2"));

  @Test
  void verifiesBothControlsAtomicallyWithOperationalReadOnlyCallsAndExplicitDeadlines() {
    var client = healthy();
    new DynamoDbVaultReadiness("vault", TIMEOUT, KEYS).verify(client);
    var request = ArgumentCaptor.forClass(TransactGetItemsRequest.class);
    verify(client).describeTable(any(DescribeTableRequest.class));
    verify(client).describeTimeToLive(any(DescribeTimeToLiveRequest.class));
    verify(client).transactGetItems(request.capture());
    assertEquals(
        List.of("C#identity", "E#identity"),
        request.getValue().transactItems().stream()
            .map(item -> item.get().key().get("pk").s())
            .toList());
    assertEquals(
        TIMEOUT,
        request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());
    verifyNoMoreInteractions(client);
  }

  @Test
  void ttlTransitionsSchemaMismatchMissingControlsAndUnavailableInstalledKeysFailClosed() {
    var client = healthy();
    var readiness = new DynamoDbVaultReadiness("vault", TIMEOUT, KEYS);
    for (var state :
        List.of(
            TimeToLiveStatus.ENABLED,
            TimeToLiveStatus.ENABLING,
            TimeToLiveStatus.DISABLING,
            TimeToLiveStatus.UNKNOWN_TO_SDK_VERSION)) {
      when(client.describeTimeToLive(any(DescribeTimeToLiveRequest.class))).thenReturn(ttl(state));
      assertThrows(IllegalStateException.class, () -> readiness.verify(client));
    }
    verify(client, never()).transactGetItems(any(TransactGetItemsRequest.class));
    when(client.describeTimeToLive(any(DescribeTimeToLiveRequest.class)))
        .thenReturn(ttl(TimeToLiveStatus.DISABLED));
    when(client.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(
            DescribeTableResponse.builder()
                .table(schema().toBuilder().tableName("wrong").build())
                .build());
    assertThrows(IllegalStateException.class, () -> readiness.verify(client));
    when(client.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(DescribeTableResponse.builder().table(schema()).build());
    when(client.transactGetItems(any(TransactGetItemsRequest.class)))
        .thenReturn(
            TransactGetItemsResponse.builder()
                .responses(ItemResponse.builder().build(), ItemResponse.builder().build())
                .build());
    assertThrows(IllegalStateException.class, () -> readiness.verify(client));
    when(client.transactGetItems(any(TransactGetItemsRequest.class)))
        .thenAnswer(call -> controls(call.getArgument(0)));
    for (Keys missing :
        List.of(
            new Keys(Set.of("identity"), Set.of("i2"), "e2", Set.of("e1", "e2")),
            new Keys(Set.of("identity"), Set.of("i1", "i2"), "e2", Set.of("e2")),
            new Keys(Set.of("identity"), Set.of("i1", "i2"), "e1", Set.of("e1", "e2"))))
      assertThrows(
          IllegalStateException.class,
          () -> new DynamoDbVaultReadiness("vault", TIMEOUT, missing).verify(client));
  }

  @Test
  void nativeVerificationWaitsForActualReadsWithoutBlockingAndSanitizesProviderErrors() {
    var client = mock(DynamoDbAsyncClient.class);
    var pending = new CompletableFuture<DescribeTableResponse>();
    when(client.describeTable(any(DescribeTableRequest.class))).thenReturn(pending);
    when(client.describeTimeToLive(any(DescribeTimeToLiveRequest.class)))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("synthetic-secret-canary")));
    var result =
        new DynamoDbVaultReadiness("vault", TIMEOUT, KEYS).verify(client).toCompletableFuture();
    assertFalse(result.isDone());
    verify(client, never()).describeTimeToLive(any(DescribeTimeToLiveRequest.class));
    pending.complete(DescribeTableResponse.builder().table(schema()).build());
    var failure = assertThrows(CompletionException.class, result::join).getCause();
    assertFalse(failure.toString().contains("canary"));
    assertNull(failure.getCause());
    verify(client, never()).transactGetItems(any(TransactGetItemsRequest.class));
  }

  @Test
  void maximumPolicyInventoryUsesTwoBoundedSnapshotsWithEachControlPairTogether() {
    var client = healthy();
    var keysets = IntStream.range(0, 64).mapToObj(i -> "keyset" + i).collect(Collectors.toSet());
    new DynamoDbVaultReadiness(
            "vault",
            TIMEOUT,
            new Keys(keysets, KEYS.fingerprints(), KEYS.wrappingWrite(), KEYS.wrappingRead()))
        .verify(client);
    var requests = ArgumentCaptor.forClass(TransactGetItemsRequest.class);
    verify(client, times(2)).transactGetItems(requests.capture());
    assertEquals(
        List.of(100, 28),
        requests.getAllValues().stream().map(r -> r.transactItems().size()).toList());
    for (var request : requests.getAllValues())
      for (int i = 0; i < request.transactItems().size(); i += 2) {
        var first = request.transactItems().get(i).get().key().get("pk").s();
        var second = request.transactItems().get(i + 1).get().key().get("pk").s();
        assertEquals(first.substring(2), second.substring(2));
      }
  }

  private static DynamoDbClient healthy() {
    var client = mock(DynamoDbClient.class);
    when(client.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(DescribeTableResponse.builder().table(schema()).build());
    when(client.describeTimeToLive(any(DescribeTimeToLiveRequest.class)))
        .thenReturn(ttl(TimeToLiveStatus.DISABLED));
    when(client.transactGetItems(any(TransactGetItemsRequest.class)))
        .thenAnswer(call -> controls(call.getArgument(0)));
    return client;
  }

  private static TableDescription schema() {
    return TableDescription.builder()
        .tableName("vault")
        .tableStatus(TableStatus.ACTIVE)
        .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
        .attributeDefinitions(
            AttributeDefinition.builder()
                .attributeName("pk")
                .attributeType(ScalarAttributeType.S)
                .build())
        .build();
  }

  private static DescribeTimeToLiveResponse ttl(TimeToLiveStatus status) {
    return DescribeTimeToLiveResponse.builder()
        .timeToLiveDescription(TimeToLiveDescription.builder().timeToLiveStatus(status).build())
        .build();
  }

  private static TransactGetItemsResponse controls(TransactGetItemsRequest request) {
    return TransactGetItemsResponse.builder()
        .responses(
            request.transactItems().stream()
                .map(
                    item -> {
                      var pk = item.get().key().get("pk").s();
                      return ItemResponse.builder()
                          .item(
                              pk.startsWith("C#")
                                  ? VaultItems.control(
                                      new FingerprintKeyState(
                                          pk.substring(2), 1, "i2", List.of("i1", "i2")))
                                  : VaultItems.wrapping(
                                      new WrappingKeyState(
                                          pk.substring(2), 1, "e2", List.of("e1", "e2"))))
                          .build();
                    })
                .toList())
        .build();
  }
}

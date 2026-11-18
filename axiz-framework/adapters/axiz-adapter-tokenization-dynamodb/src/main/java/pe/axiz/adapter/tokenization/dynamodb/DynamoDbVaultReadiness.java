package pe.axiz.adapter.tokenization.dynamodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.Get;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItem;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse;

/**
 * Read-only startup verification with the operational identity. No token reads, maintenance rights,
 * table creation, key initialization, permission simulation or remote key generation.
 */
public final class DynamoDbVaultReadiness {
  /** Identifiers of installed key material, not the material itself or a claim of remote access. */
  public record Keys(
      Set<String> keysets,
      Set<String> fingerprints,
      String wrappingWrite,
      Set<String> wrappingRead) {
    public Keys {
      keysets = Set.copyOf(keysets);
      fingerprints = Set.copyOf(fingerprints);
      wrappingRead = Set.copyOf(wrappingRead);
      if (keysets.isEmpty()
          || keysets.size() > 64
          || fingerprints.isEmpty()
          || fingerprints.size() > 16
          || wrappingRead.isEmpty()
          || wrappingRead.size() > 16
          || !wrappingRead.contains(wrappingWrite))
        throw new IllegalArgumentException("Invalid vault startup key inventory");
      keysets.forEach(VaultItems::controlKey);
      fingerprints.forEach(Keys::keyId);
      wrappingRead.forEach(Keys::keyId);
    }

    private static void keyId(String id) {
      if (!id.matches("[A-Za-z0-9_-]{1,32}"))
        throw new IllegalArgumentException("Invalid vault startup key identifier");
    }
  }

  private final Keys keys;
  private final DescribeTableRequest table;
  private final DescribeTimeToLiveRequest ttl;
  private final List<List<String>> groups;
  private final List<TransactGetItemsRequest> controls;

  public DynamoDbVaultReadiness(String tableName, Duration requestTimeout, Keys keys) {
    var requests = new VaultRequests(tableName, requestTimeout);
    this.keys = keys;
    table =
        DescribeTableRequest.builder()
            .tableName(requests.table)
            .overrideConfiguration(
                c -> c.apiCallTimeout(requests.timeout).apiCallAttemptTimeout(requests.timeout))
            .build();
    ttl =
        DescribeTimeToLiveRequest.builder()
            .tableName(requests.table)
            .overrideConfiguration(
                c -> c.apiCallTimeout(requests.timeout).apiCallAttemptTimeout(requests.timeout))
            .build();
    var sorted = keys.keysets().stream().sorted().toList();
    var batches = new ArrayList<List<String>>();
    var reads = new ArrayList<TransactGetItemsRequest>();
    // Keep C/E for each keyset in the same snapshot; at most 100 reads per transaction.
    for (int offset = 0; offset < sorted.size(); offset += 50) {
      var batch = List.copyOf(sorted.subList(offset, Math.min(offset + 50, sorted.size())));
      batches.add(batch);
      var items = new ArrayList<TransactGetItem>();
      for (String keyset : batch) {
        items.add(read(requests.table, VaultItems.controlKey(keyset)));
        items.add(read(requests.table, VaultItems.wrappingKey(keyset)));
      }
      reads.add(
          TransactGetItemsRequest.builder()
              .transactItems(items)
              .overrideConfiguration(
                  c -> c.apiCallTimeout(requests.timeout).apiCallAttemptTimeout(requests.timeout))
              .build());
    }
    groups = List.copyOf(batches);
    controls = List.copyOf(reads);
  }

  public void verify(DynamoDbClient client) {
    try {
      schema(client.describeTable(table));
      expiration(client.describeTimeToLive(ttl));
      for (int i = 0; i < controls.size(); i++)
        control(groups.get(i), client.transactGetItems(controls.get(i)));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  /** Native reads; no blocking wait or executor. The Spring startup caller bounds its wait. */
  public CompletionStage<Void> verify(DynamoDbAsyncClient client) {
    return Stages.recover(
        Stages.call(
            () ->
                Stages.compose(
                    client.describeTable(table),
                    description -> {
                      schema(description);
                      return Stages.compose(
                          client.describeTimeToLive(ttl),
                          expiration -> {
                            expiration(expiration);
                            return control(client, 0);
                          });
                    })),
        failure -> {
          throw unavailable();
        });
  }

  private CompletionStage<Void> control(DynamoDbAsyncClient client, int batch) {
    if (batch == controls.size()) return CompletableFuture.completedFuture(null);
    return Stages.compose(
        client.transactGetItems(controls.get(batch)),
        response -> {
          control(groups.get(batch), response);
          return control(client, batch + 1);
        });
  }

  private static TransactGetItem read(String tableName, String key) {
    return TransactGetItem.builder()
        .get(Get.builder().tableName(tableName).key(VaultItems.key(key)).build())
        .build();
  }

  private void schema(DescribeTableResponse response) {
    var description = response.table();
    if (description == null
        || !table.tableName().equals(description.tableName())
        || description.tableStatus() != TableStatus.ACTIVE
        || description.keySchema().size() != 1
        || description.keySchema().getFirst().keyType() != KeyType.HASH
        || !description.keySchema().getFirst().attributeName().equals("pk")
        || description.attributeDefinitions().size() != 1
        || !description.attributeDefinitions().getFirst().attributeName().equals("pk")
        || description.attributeDefinitions().getFirst().attributeType() != ScalarAttributeType.S
        || !description.globalSecondaryIndexes().isEmpty()
        || !description.localSecondaryIndexes().isEmpty()
        || !description.replicas().isEmpty()) throw unavailable();
  }

  private static void expiration(DescribeTimeToLiveResponse response) {
    var description = response.timeToLiveDescription();
    // DISABLING may still delete rows. Unknown/absent states are not evidence of safety.
    if (description == null || description.timeToLiveStatus() != TimeToLiveStatus.DISABLED)
      throw unavailable();
  }

  private void control(List<String> batch, TransactGetItemsResponse response) {
    if (response.responses().size() != batch.size() * 2) throw unavailable();
    for (int i = 0; i < batch.size(); i++) {
      String keyset = batch.get(i);
      var fingerprint = VaultItems.control(keyset, response.responses().get(i * 2).item());
      var wrapping = VaultItems.wrapping(keyset, response.responses().get(i * 2 + 1).item());
      if (!keys.fingerprints().containsAll(fingerprint.readKeyIds())
          || !keys.wrappingWrite().equals(wrapping.writeKeyId())
          || !keys.wrappingRead().containsAll(wrapping.readKeyIds())) throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Token vault storage/key readiness was not established");
  }
}

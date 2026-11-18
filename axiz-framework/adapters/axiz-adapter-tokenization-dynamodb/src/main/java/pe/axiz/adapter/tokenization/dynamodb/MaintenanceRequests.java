package pe.axiz.adapter.tokenization.dynamodb;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultKeyTransition;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import pe.axiz.core.tokenization.vault.WrappingKeyTransition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/** Privileged planning; operational adapters have no entry point for these requests. */
final class MaintenanceRequests {
  private final VaultRequests requests;

  MaintenanceRequests(VaultRequests requests) {
    this.requests = requests;
  }

  GetItemRequest wrappingGet(String keyset) {
    return requests.get(VaultItems.wrappingKey(keyset));
  }

  TransactWriteItemsRequest initializeWrapping(WrappingKeyState state) {
    return initialize(
        VaultItems.wrapping(state),
        "E",
        state.keysetId(),
        state.version(),
        state.writeKeyId(),
        state.readKeyIds());
  }

  TransactWriteItemsRequest initialize(FingerprintKeyState state) {
    return initialize(
        VaultItems.control(state),
        "C",
        state.keysetId(),
        state.version(),
        state.writeKeyId(),
        state.readKeyIds());
  }

  private TransactWriteItemsRequest initialize(
      Map<String, AttributeValue> item,
      String kind,
      String keyset,
      long version,
      String writeKey,
      List<String> reads) {
    if (version != 1 || !reads.equals(List.of(writeKey)))
      throw new IllegalArgumentException("Invalid initial key state");
    return requests.transaction(
        List.of(
            TransactWriteItem.builder()
                .put(
                    Put.builder()
                        .tableName(requests.table)
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)")
                        .build())
                .build(),
            initialized(kind, keyset),
            reserve(kind, keyset, writeKey)));
  }

  TransactWriteItemsRequest transitionWrapping(WrappingKeyTransition transition) {
    var expected = transition.expected();
    var barrier =
        requests
            .controlBarrier(
                new FingerprintKeyState(
                    expected.keysetId(),
                    expected.version(),
                    expected.writeKeyId(),
                    expected.readKeyIds()))
            .conditionCheck();
    var next = transition.next();
    return transition(
        VaultItems.wrapping(next),
        barrier,
        "E",
        next.keysetId(),
        next.writeKeyId().equals(expected.writeKeyId()) ? null : next.writeKeyId());
  }

  TransactWriteItemsRequest transition(VaultKeyTransition transition) {
    var next = transition.next();
    return transition(
        VaultItems.control(next),
        requests.controlBarrier(transition.expected()).conditionCheck(),
        "C",
        next.keysetId(),
        next.writeKeyId().equals(transition.expected().writeKeyId()) ? null : next.writeKeyId());
  }

  private TransactWriteItemsRequest transition(
      Map<String, AttributeValue> next,
      ConditionCheck barrier,
      String kind,
      String keyset,
      @Nullable String newKey) {
    var items = new ArrayList<TransactWriteItem>();
    items.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(requests.table)
                    .item(next)
                    .conditionExpression(barrier.conditionExpression())
                    .expressionAttributeNames(barrier.expressionAttributeNames())
                    .expressionAttributeValues(barrier.expressionAttributeValues())
                    .build())
            .build());
    if (newKey != null) items.add(reserve(kind, keyset, newKey));
    return requests.transaction(items);
  }

  private TransactWriteItem initialized(String kind, String keyset) {
    // A deleted control must be restored and reconciled, never silently restarted at version one.
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(requests.table)
                .item(
                    Map.of(
                        "pk",
                        VaultItems.s("I#" + kind + "#" + keyset),
                        "schema",
                        VaultItems.n(1),
                        "kind",
                        VaultItems.s(kind),
                        "keyset",
                        VaultItems.s(keyset)))
                .conditionExpression("attribute_not_exists(pk)")
                .build())
        .build();
  }

  private TransactWriteItem reserve(String kind, String keyset, String keyId) {
    // Permanent key-id reservation: removal from the read set must never permit ABA reuse.
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(requests.table)
                .item(
                    Map.of(
                        "pk",
                        VaultItems.s("R#" + kind + "#" + keyset + "#" + keyId),
                        "schema",
                        VaultItems.n(1),
                        "kind",
                        VaultItems.s(kind),
                        "keyset",
                        VaultItems.s(keyset),
                        "keyId",
                        VaultItems.s(keyId)))
                .conditionExpression("attribute_not_exists(pk)")
                .build())
        .build();
  }

  TransactWriteItemsRequest replace(
      VaultRecord expected, VaultRecord replacement, FingerprintKeyState state) {
    validate(expected, replacement, state);
    var items = new ArrayList<TransactWriteItem>();
    items.add(requests.controlBarrier(state));
    // Share the same ABA fence as deletion, including the exact ciphertext incarnation.
    var fence = requests.recordFence(expected);
    var values = new HashMap<>(fence.expressionAttributeValues());
    values.put(":created", VaultItems.s(expected.createdAt().toString()));
    values.put(":expires", VaultItems.s(expected.expiresAt().toString()));
    values.put(":metadata", VaultItems.record(expected).get("metadata"));
    items.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(requests.table)
                    .item(VaultItems.record(replacement))
                    .conditionExpression(
                        fence.conditionExpression()
                            + " AND createdAt = :created AND expiresAt = :expires AND metadata ="
                            + " :metadata")
                    .expressionAttributeNames(fence.expressionAttributeNames())
                    .expressionAttributeValues(values)
                    .build())
            .build());
    var all = new ArrayList<>(expected.fingerprints());
    for (KeyedHash hash : replacement.fingerprints()) if (!all.contains(hash)) all.add(hash);
    for (KeyedHash hash : all) {
      Map<String, String> names = Map.of("#token", "token", "#scope", "scope", "#schema", "schema");
      var ownership =
          Map.of(
              ":token",
              VaultItems.s(expected.token().reveal()),
              ":scope",
              VaultItems.s(expected.scope().encoded()),
              ":schema",
              VaultItems.n(1),
              ":keyset",
              VaultItems.s(expected.keysetId()),
              ":keyId",
              VaultItems.s(hash.keyId()));
      String condition =
          "#token = :token AND #scope = :scope AND #schema = :schema"
              + " AND keyset = :keyset AND keyId = :keyId";
      var key = VaultItems.key(VaultItems.fingerprintKey(expected.scope(), hash));
      if (!expected.fingerprints().contains(hash))
        items.add(
            TransactWriteItem.builder()
                .put(
                    Put.builder()
                        .tableName(requests.table)
                        .item(VaultItems.fingerprint(replacement, hash))
                        .conditionExpression("attribute_not_exists(pk) OR (" + condition + ")")
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(ownership)
                        .build())
                .build());
      else if (replacement.fingerprints().contains(hash))
        items.add(
            TransactWriteItem.builder()
                .conditionCheck(
                    ConditionCheck.builder()
                        .tableName(requests.table)
                        .key(key)
                        .conditionExpression(condition)
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(ownership)
                        .build())
                .build());
      else
        items.add(
            TransactWriteItem.builder()
                .delete(
                    Delete.builder()
                        .tableName(requests.table)
                        .key(key)
                        .conditionExpression(condition)
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(ownership)
                        .build())
                .build());
    }
    items.add(
        requests.wrappingBarrier(
            replacement.keysetId(),
            KeyringDataKeyProvider.envelopeKeyId(replacement.ciphertext()),
            !Arrays.equals(expected.ciphertext(), replacement.ciphertext())));
    return requests.transaction(items);
  }

  private void validate(VaultRecord old, VaultRecord next, FingerprintKeyState state) {
    if (!old.token().equals(next.token())
        || !old.scope().equals(next.scope())
        || !old.keysetId().equals(state.keysetId())
        || !old.keysetId().equals(next.keysetId())
        || next.revision() != Math.addExact(old.revision(), 1)
        || !old.createdAt().equals(next.createdAt())
        || !old.expiresAt().equals(next.expiresAt())
        || !old.metadata().equals(next.metadata()))
      throw new IllegalArgumentException("Invalid vault record replacement");
    boolean removed = !next.fingerprints().containsAll(old.fingerprints());
    if (removed
        && next.fingerprints().stream().noneMatch(hash -> hash.keyId().equals(state.writeKeyId())))
      throw new IllegalArgumentException("Current fingerprint must be retained");
    var oldIds = new HashSet<>(old.fingerprints().stream().map(KeyedHash::keyId).toList());
    for (KeyedHash hash : old.fingerprints())
      if (!state.readKeyIds().contains(hash.keyId()))
        throw new IllegalArgumentException("Unknown stored fingerprint");
    for (KeyedHash hash : next.fingerprints()) {
      if (!state.readKeyIds().contains(hash.keyId()))
        throw new IllegalArgumentException("Inactive fingerprint");
      if (!old.fingerprints().contains(hash)
          && (oldIds.contains(hash.keyId()) || !hash.keyId().equals(state.writeKeyId())))
        throw new IllegalArgumentException("Only the current fingerprint can be added");
    }
  }

  ScanRequest scan(String keyset, @Nullable String keyId, @Nullable String cursor, int limit) {
    VaultItems.controlKey(keyset);
    if (limit < 1 || limit > 100 || (keyId != null && !keyId.matches("[A-Za-z0-9_-]{1,32}")))
      throw new IllegalArgumentException("Invalid maintenance scan");
    var values = new HashMap<String, AttributeValue>();
    values.put(":prefix", VaultItems.s(keyId == null ? "T#" : "F#"));
    values.put(":keyset", VaultItems.s(keyset));
    if (keyId != null) values.put(":keyId", VaultItems.s(keyId));
    var builder =
        ScanRequest.builder()
            .tableName(requests.table)
            .consistentRead(true)
            .limit(limit)
            .filterExpression(
                "begins_with(pk, :prefix) AND keyset = :keyset"
                    + (keyId == null ? "" : " AND keyId = :keyId"))
            .expressionAttributeValues(values)
            .overrideConfiguration(
                config ->
                    config
                        .apiCallTimeout(requests.timeout)
                        .apiCallAttemptTimeout(requests.timeout));
    if (keyId != null) builder.projectionExpression("pk");
    if (cursor != null) builder.exclusiveStartKey(decode(keyset, cursor));
    return builder.build();
  }

  @Nullable String cursor(String keyset, Map<String, AttributeValue> key) {
    if (key.isEmpty()) return null;
    if (!key.keySet().equals(Set.of("pk"))) throw new VaultIntegrityException();
    String pk = VaultItems.text(key, "pk", 600);
    if (!pk.matches("[TFCERI]#[A-Za-z0-9_:#.@/\\-]+")) throw new VaultIntegrityException();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((keyset + "\n" + pk).getBytes(StandardCharsets.US_ASCII));
  }

  private Map<String, AttributeValue> decode(String keyset, String cursor) {
    if (cursor.isEmpty() || cursor.length() > 1024)
      throw new IllegalArgumentException("Invalid scan cursor");
    String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
    if (!value.startsWith(keyset + "\n"))
      throw new IllegalArgumentException("Invalid scan cursor scope");
    var key = VaultItems.key(value.substring(keyset.length() + 1));
    if (!cursor.equals(cursor(keyset, key)))
      throw new IllegalArgumentException("Noncanonical scan cursor");
    return key;
  }
}

package pe.axiz.adapter.tokenization.dynamodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.SecureTokenGenerator;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.core.tokenization.vault.VaultUncertainException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.Get;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItem;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

/** Shared bounded request planning and outcome classification; no transport or retries. */
final class VaultRequests {
  static final int ATTEMPTS = 3;
  final String table;
  final Duration timeout;
  private final SecureTokenGenerator ids = new SecureTokenGenerator();

  VaultRequests(String table, Duration timeout) {
    if (table == null || !table.matches("[A-Za-z0-9_.-]{3,255}"))
      throw new IllegalArgumentException("Invalid vault table name");
    this.table = table;
    if (timeout == null
        || timeout.compareTo(Duration.ofMillis(1)) < 0
        || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException(
          "Vault request timeout must be between 1 ms and 30 seconds");
    this.timeout = timeout;
  }

  GetItemRequest get(String key) {
    return GetItemRequest.builder()
        .tableName(table)
        .key(VaultItems.key(key))
        .consistentRead(true)
        .overrideConfiguration(
            config -> config.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout))
        .build();
  }

  TransactGetItemsRequest references(
      VaultScope scope, List<KeyedHash> candidates, @Nullable Token owner) {
    VaultItems.candidates(scope, candidates);
    var items = new ArrayList<TransactGetItem>();
    for (KeyedHash hash : candidates) items.add(read(VaultItems.fingerprintKey(scope, hash)));
    if (owner != null) items.add(read(VaultItems.tokenKey(owner)));
    return TransactGetItemsRequest.builder()
        .transactItems(items)
        .overrideConfiguration(
            config -> config.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout))
        .build();
  }

  private TransactGetItem read(String key) {
    return TransactGetItem.builder()
        .get(Get.builder().tableName(table).key(VaultItems.key(key)).build())
        .build();
  }

  Optional<Token> owner(
      VaultScope scope, List<KeyedHash> candidates, List<ItemResponse> items, boolean withRecord) {
    if (items.size() != candidates.size() + (withRecord ? 1 : 0))
      throw new VaultIntegrityException();
    Token owner = null;
    for (int i = 0; i < candidates.size(); i++) {
      var item = items.get(i).item();
      if (item.isEmpty()) continue;
      Token next = VaultItems.fingerprint(item, scope, candidates.get(i));
      if (owner != null && !owner.equals(next)) throw new VaultIntegrityException();
      owner = next;
    }
    return Optional.ofNullable(owner);
  }

  VaultRecord association(
      VaultScope scope, List<KeyedHash> candidates, Token token, List<ItemResponse> items) {
    VaultRecord record = VaultItems.record(items.getLast().item());
    if (!record.scope().equals(scope) || !record.token().equals(token))
      throw new VaultIntegrityException();
    // Every live index in this serializable snapshot must belong to the same record revision.
    for (int i = 0; i < candidates.size(); i++)
      if (!items.get(i).item().isEmpty()
          && (!record.fingerprints().contains(candidates.get(i))
              || !VaultItems.text(items.get(i).item(), "keyset", 64).equals(record.keysetId())))
        throw new VaultIntegrityException();
    return record;
  }

  TransactWriteItemsRequest insert(
      VaultRecord record, FingerprintKeyState state, List<KeyedHash> otherRead) {
    if (record.revision() != 1
        || record.fingerprints().size() != 1
        || !record.keysetId().equals(state.keysetId())
        || !record.fingerprints().getFirst().keyId().equals(state.writeKeyId()))
      throw new IllegalArgumentException("Invalid initial vault association");
    var hashes = new ArrayList<>(record.fingerprints());
    hashes.addAll(otherRead);
    VaultItems.candidates(record.scope(), hashes);
    if (!new HashSet<>(hashes.stream().map(KeyedHash::keyId).toList())
        .equals(new HashSet<>(state.readKeyIds())))
      throw new IllegalArgumentException("All active read fingerprints are required");
    var items = new ArrayList<TransactWriteItem>();
    items.add(controlBarrier(state));
    items.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(table)
                    .item(VaultItems.record(record))
                    .conditionExpression("attribute_not_exists(pk)")
                    .build())
            .build());
    items.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(table)
                    .item(VaultItems.fingerprint(record, record.fingerprints().getFirst()))
                    .conditionExpression("attribute_not_exists(pk)")
                    .build())
            .build());
    for (KeyedHash hash : otherRead)
      items.add(
          TransactWriteItem.builder()
              .conditionCheck(
                  ConditionCheck.builder()
                      .tableName(table)
                      .key(VaultItems.key(VaultItems.fingerprintKey(record.scope(), hash)))
                      .conditionExpression("attribute_not_exists(pk)")
                      .build())
              .build());
    items.add(
        wrappingBarrier(
            record.keysetId(), KeyringDataKeyProvider.envelopeKeyId(record.ciphertext()), true));
    return transaction(items);
  }

  TransactWriteItem wrappingBarrier(String keyset, String route, boolean writing) {
    return TransactWriteItem.builder()
        .conditionCheck(
            ConditionCheck.builder()
                .tableName(table)
                .key(VaultItems.key(VaultItems.wrappingKey(keyset)))
                .conditionExpression(
                    "#schema = :schema AND keyset = :keyset AND "
                        + (writing ? "writeKey = :route" : "contains(readKeys, :route)"))
                .expressionAttributeNames(Map.of("#schema", "schema"))
                .expressionAttributeValues(
                    Map.of(
                        ":schema",
                        VaultItems.n(1),
                        ":keyset",
                        VaultItems.s(keyset),
                        ":route",
                        VaultItems.s(route)))
                .build())
        .build();
  }

  TransactWriteItem controlBarrier(FingerprintKeyState state) {
    return TransactWriteItem.builder()
        .conditionCheck(
            ConditionCheck.builder()
                .tableName(table)
                .key(VaultItems.key(VaultItems.controlKey(state.keysetId())))
                .conditionExpression(
                    "#schema = :schema AND #version = :version AND writeKey = :write AND readKeys ="
                        + " :read AND keyset = :keyset")
                .expressionAttributeNames(Map.of("#schema", "schema", "#version", "version"))
                .expressionAttributeValues(
                    Map.of(
                        ":keyset",
                        VaultItems.s(state.keysetId()),
                        ":schema",
                        VaultItems.n(1),
                        ":version",
                        VaultItems.n(state.version()),
                        ":write",
                        VaultItems.s(state.writeKeyId()),
                        ":read",
                        VaultItems.strings(state.readKeyIds())))
                .build())
        .build();
  }

  Delete recordFence(VaultRecord expected) {
    var stored = VaultItems.record(expected);
    return Delete.builder()
        .tableName(table)
        .key(VaultItems.key(VaultItems.tokenKey(expected.token())))
        .conditionExpression(
            "#revision = :revision AND #scope = :scope AND keyset = :keyset"
                + " AND fingerprints = :hashes AND ciphertext = :ciphertext"
                + " AND createdAt = :created AND expiresAt = :expires")
        .expressionAttributeNames(Map.of("#revision", "revision", "#scope", "scope"))
        .expressionAttributeValues(
            Map.of(
                ":revision",
                VaultItems.n(expected.revision()),
                ":scope",
                VaultItems.s(expected.scope().encoded()),
                ":keyset",
                VaultItems.s(expected.keysetId()),
                ":hashes",
                VaultItems.strings(
                    expected.fingerprints().stream().map(KeyedHash::encoded).toList()),
                ":ciphertext",
                stored.get("ciphertext"),
                ":created",
                stored.get("createdAt"),
                ":expires",
                stored.get("expiresAt")))
        .build();
  }

  TransactWriteItemsRequest delete(VaultRecord expected) {
    var items = new ArrayList<TransactWriteItem>();
    items.add(TransactWriteItem.builder().delete(recordFence(expected)).build());
    for (KeyedHash hash : expected.fingerprints())
      items.add(
          TransactWriteItem.builder()
              .delete(
                  Delete.builder()
                      .tableName(table)
                      .key(VaultItems.key(VaultItems.fingerprintKey(expected.scope(), hash)))
                      .conditionExpression("#token = :token AND #scope = :scope")
                      .expressionAttributeNames(Map.of("#token", "token", "#scope", "scope"))
                      .expressionAttributeValues(
                          Map.of(
                              ":token",
                              VaultItems.s(expected.token().reveal()),
                              ":scope",
                              VaultItems.s(expected.scope().encoded())))
                      .build())
              .build());
    return transaction(items);
  }

  TransactWriteItemsRequest transaction(List<TransactWriteItem> items) {
    // This token only makes transport retries of this exact AWS request idempotent for AWS's
    // window.
    // It is not the public operationId and does not implement application-level UUID idempotency.
    return TransactWriteItemsRequest.builder()
        .clientRequestToken(ids.operationId().toString())
        .transactItems(items)
        .overrideConfiguration(
            config -> config.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout))
        .build();
  }

  static List<CancellationReason> reasons(TransactionCanceledException failure, int size) {
    List<CancellationReason> reasons = failure.cancellationReasons();
    if (reasons.size() != size) throw unavailable();
    for (CancellationReason reason : reasons)
      if (!List.of("None", "ConditionalCheckFailed", "TransactionConflict").contains(reason.code()))
        throw unavailable();
    return reasons;
  }

  static boolean condition(List<CancellationReason> reasons, int index) {
    return "ConditionalCheckFailed".equals(reasons.get(index).code());
  }

  static boolean conflict(List<CancellationReason> reasons) {
    return reasons.stream().anyMatch(reason -> "TransactionConflict".equals(reason.code()));
  }

  static RuntimeException unavailable() {
    return TokenErrors.failure(TokenErrors.UNAVAILABLE);
  }

  static RuntimeException readFailure(Throwable failure) {
    return failure instanceof VaultIntegrityException integrity ? integrity : unavailable();
  }

  static RuntimeException writeFailure(Throwable failure) {
    Objects.requireNonNull(failure);
    if (failure instanceof TransactionCanceledException)
      return unavailable(); // Confirmed rollback, but no recognized conditional outcome.
    if (failure instanceof DynamoDbException service
        && service.statusCode() >= 400
        && service.statusCode() < 500
        && !(failure instanceof TransactionInProgressException)) return unavailable();
    return new VaultUncertainException(); // Timeout, transport loss or 5xx: commit has not been
    // disproved.
  }
}

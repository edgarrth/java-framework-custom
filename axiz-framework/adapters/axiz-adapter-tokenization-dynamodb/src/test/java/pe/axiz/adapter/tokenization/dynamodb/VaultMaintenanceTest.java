package pe.axiz.adapter.tokenization.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultTest.cancelled;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.SCOPE;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.STATE;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.hashes;
import static pe.axiz.adapter.tokenization.dynamodb.VaultRequestsTest.record;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.MigrationOutcome;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultKeyTransition;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.core.tokenization.vault.VaultUncertainException;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import pe.axiz.core.tokenization.vault.WrappingKeyTransition;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

class VaultMaintenanceTest {
  private final MaintenanceRequests requests =
      new MaintenanceRequests(new VaultRequests("token-vault", Duration.ofSeconds(1)));
  private final DynamoDbClient client = mock(DynamoDbClient.class);
  private final DynamoDbVaultMaintenance maintenance =
      new DynamoDbVaultMaintenance(client, "token-vault", Duration.ofSeconds(1));
  private static final FingerprintKeyState INITIAL =
      new FingerprintKeyState("main", 1, "old", List.of("old"));
  private static final WrappingKeyState WRAPPING =
      new WrappingKeyState("main", 1, "dek-old", List.of("dek-old"));

  private static VaultRecord.Builder copy(VaultRecord value) {
    return VaultRecord.builder()
        .token(value.token())
        .scope(value.scope())
        .keysetId(value.keysetId())
        .fingerprints(value.fingerprints())
        .revision(value.revision() + 1)
        .ciphertext(value.ciphertext())
        .createdAt(value.createdAt())
        .expiresAt(value.expiresAt())
        .metadata(value.metadata());
  }

  @Test
  void initializationAndTransitionsPreservePermanentReservationsAndExactStateFences() {
    var init = requests.initialize(INITIAL);
    assertThat(init.transactItems())
        .hasSize(3)
        .allSatisfy(
            action -> {
              assertThat(action.put().tableName()).isEqualTo("token-vault");
              assertThat(action.put().conditionExpression()).isEqualTo("attribute_not_exists(pk)");
            });
    assertThat(init.transactItems().get(1).put().item().get("pk").s()).isEqualTo("I#C#main");
    assertThat(init.transactItems().get(2).put().item().get("pk").s()).isEqualTo("R#C#main#old");
    assertThat(
            requests.initializeWrapping(WRAPPING).transactItems().get(2).put().item().get("pk").s())
        .isEqualTo("R#E#main#dek-old");
    for (FingerprintKeyState invalid :
        List.of(new FingerprintKeyState("main", 2, "old", List.of("old")), STATE)) {
      assertThatThrownBy(() -> requests.initialize(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var transition = mock(VaultKeyTransition.class);
    when(transition.expected()).thenReturn(INITIAL);
    when(transition.next())
        .thenReturn(new FingerprintKeyState("main", 2, "new", List.of("old", "new")));
    var rotated = requests.transition(transition);
    assertThat(rotated.transactItems()).hasSize(2);
    assertThat(rotated.transactItems().getFirst().put().conditionExpression())
        .contains("readKeys = :read");
    assertThat(rotated.transactItems().getLast().put().item().get("pk").s())
        .isEqualTo("R#C#main#new");
    when(transition.next()).thenReturn(new FingerprintKeyState("main", 2, "old", List.of("old")));
    assertThat(requests.transition(transition).transactItems()).hasSize(1);
    var wrapping = mock(WrappingKeyTransition.class);
    when(wrapping.expected()).thenReturn(WRAPPING);
    when(wrapping.next())
        .thenReturn(new WrappingKeyState("main", 2, "dek-new", List.of("dek-old", "dek-new")));
    assertThat(requests.transitionWrapping(wrapping).transactItems()).hasSize(2);
    when(wrapping.next())
        .thenReturn(new WrappingKeyState("main", 2, "dek-old", List.of("dek-old")));
    assertThat(requests.transitionWrapping(wrapping).transactItems()).hasSize(1);
    doReturn(TransactWriteItemsResponse.builder().build())
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThat(maintenance.initialize(INITIAL)).isTrue();
    assertThat(maintenance.initializeWrapping(WRAPPING)).isTrue();
    assertThat(maintenance.transition(transition)).isTrue();
    assertThat(maintenance.transitionWrapping(wrapping)).isTrue();
  }

  @Test
  void migrationFencesCiphertextMetadataAndIndexOwnershipForAddKeepAndPrune() {
    var original =
        copy(record()).revision(1).fingerprints(List.of(hashes(SCOPE).getLast())).build();
    var expanded = copy(original).fingerprints(hashes(SCOPE)).build();
    var request = requests.replace(original, expanded, STATE);
    assertThat(request.transactItems()).hasSize(5);
    assertThat(request.transactItems().get(1).put().conditionExpression())
        .contains(
            "ciphertext = :ciphertext",
            "createdAt = :created",
            "expiresAt = :expires",
            "metadata = :metadata");
    assertThat(request.transactItems().get(2).conditionCheck()).isNotNull();
    assertThat(request.transactItems().get(3).put().conditionExpression())
        .contains("attribute_not_exists(pk)", "#token = :token");
    var pruned = copy(expanded).fingerprints(List.of(hashes(SCOPE).getFirst())).build();
    assertThat(requests.replace(expanded, pruned, STATE).transactItems())
        .anySatisfy(action -> assertThat(action.delete()).isNotNull());
    var rewrapped = copy(pruned).ciphertext(VaultRequestsTest.ciphertext()).build();
    assertThat(
            requests
                .replace(pruned, rewrapped, STATE)
                .transactItems()
                .getLast()
                .conditionCheck()
                .expressionAttributeValues())
        .isNotEmpty();
  }

  @Test
  void migrationCannotChangeIdentityRetentionMetadataOrIntroduceUnapprovedIndexes() {
    var old = record();
    List<Consumer<VaultRecord.Builder>> changes =
        List.of(
            b ->
                b.token(
                    Token.fromEntropy(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})),
            b ->
                b.scope(
                    new VaultScope(SCOPE.type(), "other", SCOPE.domain(), SCOPE.profileId(), 1)),
            b -> b.keysetId("other"),
            b -> b.revision(old.revision()),
            b -> b.createdAt(old.createdAt().plusSeconds(1)),
            b -> b.expiresAt(old.expiresAt().plusSeconds(1)),
            b -> b.metadata(Map.of("kind", "changed")));
    for (var change : changes) {
      var builder = copy(old);
      change.accept(builder);
      assertThatThrownBy(() -> requests.replace(old, builder.build(), STATE))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                requests.replace(
                    old,
                    copy(old).build(),
                    new FingerprintKeyState("other", 1, "new", List.of("new"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                requests.replace(
                    old, copy(old).fingerprints(List.of(hashes(SCOPE).getLast())).build(), STATE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Current fingerprint");
    var unknown =
        KeyedHash.parse(hashes(SCOPE).getFirst().encoded().replace("axi1:new:", "axi1:unknown:"));
    assertThatThrownBy(
            () ->
                requests.replace(
                    copy(old).fingerprints(List.of(unknown)).build(),
                    copy(old).revision(3).build(),
                    STATE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown stored");
    assertThatThrownBy(
            () ->
                requests.replace(
                    old,
                    copy(old).fingerprints(List.of(hashes(SCOPE).getFirst(), unknown)).build(),
                    STATE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Inactive");
    assertThatThrownBy(
            () -> requests.replace(old, copy(old).fingerprints(hashes(SCOPE)).build(), STATE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Only the current");
    String encoded = hashes(SCOPE).getFirst().encoded();
    var otherDigest =
        KeyedHash.parse(
            encoded.substring(0, encoded.lastIndexOf(':') + 1)
                + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
    assertThatThrownBy(
            () ->
                requests.replace(old, copy(old).fingerprints(List.of(otherDigest)).build(), STATE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Only the current");
  }

  @Test
  void maintenancePaginationIsConsistentBoundedCanonicalAndScoped() {
    var key = VaultItems.key("T#synthetic");
    String cursor = requests.cursor("main", key);
    var scan = requests.scan("main", null, cursor, 100);
    assertThat(scan.consistentRead()).isTrue();
    assertThat(scan.exclusiveStartKey()).isEqualTo(key);
    assertThat(scan.limit()).isEqualTo(100);
    assertThat(scan.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow())
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(requests.scan("main", "old", null, 1).projectionExpression()).isEqualTo("pk");
    assertThat(requests.cursor("main", Map.of())).isNull();
    for (int limit : new int[] {0, 101}) {
      assertThatThrownBy(() -> requests.scan("main", null, null, limit))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> requests.scan("main", "!", null, 10))
        .isInstanceOf(IllegalArgumentException.class);
    for (String bad :
        List.of(
            "",
            "x".repeat(1025),
            "!",
            requests.cursor("other", key),
            Base64.getUrlEncoder()
                .encodeToString("main\nT#synthetic".getBytes(StandardCharsets.US_ASCII)))) {
      assertThatThrownBy(() -> requests.scan("main", null, bad, 10))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> requests.cursor("main", Map.of("other", VaultItems.s("x"))))
        .isInstanceOf(VaultIntegrityException.class);
    assertThatThrownBy(() -> requests.cursor("main", VaultItems.key("invalid")))
        .isInstanceOf(VaultIntegrityException.class);
    doReturn(
            ScanResponse.builder().items(VaultItems.record(record())).lastEvaluatedKey(key).build())
        .when(client)
        .scan(any(ScanRequest.class));
    assertThat(maintenance.scan("main", null, 10).records())
        .extracting(VaultRecord::token)
        .containsExactly(record().token());
    assertThat(maintenance.references("main", "new", cursor, 10).references()).isEqualTo(1);
    doReturn(GetItemResponse.builder().item(VaultItems.wrapping(WRAPPING)).build())
        .when(client)
        .getItem(any(GetItemRequest.class));
    assertThat(maintenance.wrappingState("main")).isEqualTo(WRAPPING);
    doThrow(SdkClientException.create("synthetic-secret"))
        .when(client)
        .scan(any(ScanRequest.class));
    assertThatThrownBy(() -> maintenance.scan("main", null, 10))
        .isInstanceOf(AxizException.class)
        .hasMessageNotContaining("synthetic-secret");
    assertThatThrownBy(() -> maintenance.references("main", "new", null, 10))
        .isInstanceOf(AxizException.class);
    doThrow(SdkClientException.create("synthetic-secret"))
        .when(client)
        .getItem(any(GetItemRequest.class));
    assertThatThrownBy(() -> maintenance.wrappingState("main")).isInstanceOf(AxizException.class);
  }

  @Test
  void controlConflictRetriesAreBoundedAndTransportFailuresRemainUncertain() {
    doThrow(cancelled("None", "ConditionalCheckFailed", "None"))
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThat(maintenance.initialize(INITIAL)).isFalse();
    reset(client);
    doThrow(cancelled("TransactionConflict", "None", "None"))
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThatThrownBy(() -> maintenance.initialize(INITIAL)).isInstanceOf(AxizException.class);
    verify(client, times(VaultRequests.ATTEMPTS))
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    doThrow(cancelled("ProvisionedThroughputExceeded", "None", "None"))
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThatThrownBy(() -> maintenance.initialize(INITIAL)).isInstanceOf(AxizException.class);
    doThrow(SdkClientException.create("secret"))
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThatThrownBy(() -> maintenance.initialize(INITIAL))
        .isInstanceOf(VaultUncertainException.class);
  }

  @Test
  void replacementDistinguishesStaleControlChangedRecordAndCorruptReferences() {
    var old = record();
    var next = copy(old).build();
    int count = requests.replace(old, next, STATE).transactItems().size();
    for (int index = 0; index < count; index++) {
      String[] reasons = new String[count];
      Arrays.fill(reasons, "None");
      reasons[index] = "ConditionalCheckFailed";
      doThrow(cancelled(reasons))
          .when(client)
          .transactWriteItems(any(TransactWriteItemsRequest.class));
      MigrationOutcome expected =
          index == 0 || index == count - 1
              ? MigrationOutcome.STALE_KEY_STATE
              : index == 1 ? MigrationOutcome.CHANGED : MigrationOutcome.INTEGRITY_FAILURE;
      assertThat(maintenance.replace(old, next, STATE)).isEqualTo(expected);
    }
    String[] conflicts = new String[count];
    Arrays.fill(conflicts, "None");
    conflicts[0] = "TransactionConflict";
    reset(client);
    doThrow(cancelled(conflicts))
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThatThrownBy(() -> maintenance.replace(old, next, STATE))
        .isInstanceOf(AxizException.class);
    verify(client, times(VaultRequests.ATTEMPTS))
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    conflicts[0] = "ProvisionedThroughputExceeded";
    doThrow(cancelled(conflicts))
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThatThrownBy(() -> maintenance.replace(old, next, STATE))
        .isInstanceOf(AxizException.class);
    doThrow(SdkClientException.create("secret"))
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThatThrownBy(() -> maintenance.replace(old, next, STATE))
        .isInstanceOf(VaultUncertainException.class);
    doReturn(TransactWriteItemsResponse.builder().build())
        .when(client)
        .transactWriteItems(any(TransactWriteItemsRequest.class));
    assertThat(maintenance.replace(old, next, STATE)).isEqualTo(MigrationOutcome.MIGRATED);
  }
}

package pe.axiz.adapter.tokenization.dynamodb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultScope;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ItemResponse;

class VaultRequestsTest {
  static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", new TokenDomain("payments"), "dni.pe", 1);
  static final FingerprintKeyState STATE =
      new FingerprintKeyState("main", 1, "new", List.of("new", "old"));
  static final Token TOKEN = Token.fromEntropy(new byte[16]);

  static List<KeyedHash> hashes(VaultScope scope) {
    try (var indexes = new BlindIndex(Map.of("new", new byte[32], "old", new byte[32]), "new")) {
      return indexes.candidates("00883344", scope.indexScope());
    }
  }

  static byte[] ciphertext() {
    var provider =
        new DataKeyProvider() {
          @Override
          public DataKey generate(String context) {
            return new DataKey(new byte[32], new byte[] {1});
          }

          @Override
          public byte[] decrypt(byte[] wrapped, String context) {
            return new byte[32];
          }
        };
    try (var encryptor =
        new EnvelopeEncryptor(
            new KeyringDataKeyProvider(Map.of("dek-old", provider), "dek-old"),
            Duration.ofMinutes(1),
            100,
            Clock.systemUTC())) {
      return encryptor.encrypt(new byte[] {1}, "purpose:token-vault", SCOPE.binding(TOKEN));
    }
  }

  static VaultRecord record() {
    return VaultRecord.builder()
        .token(TOKEN)
        .scope(SCOPE)
        .keysetId("main")
        .fingerprints(List.of(hashes(SCOPE).getFirst()))
        .revision(1)
        .ciphertext(ciphertext())
        .createdAt(Instant.parse("2026-11-17T00:00:00Z"))
        .expiresAt(Instant.parse("2026-12-17T00:00:00Z"))
        .metadata(Map.of())
        .build();
  }

  static ItemResponse item(Map<String, AttributeValue> value) {
    return ItemResponse.builder().item(value).build();
  }

  @Test
  void transactionChecksFullKeyStateAndHasExactlyOneActionPerItem() {
    var builder = new VaultRequests("token-vault", Duration.ofSeconds(2));
    var put = builder.insert(record(), STATE, List.of(hashes(SCOPE).getLast()));
    assertEquals(5, put.transactItems().size());
    var keys = new HashSet<String>();
    put.transactItems()
        .forEach(
            action -> {
              Map<String, AttributeValue> item =
                  action.put() != null ? action.put().item() : action.conditionCheck().key();
              assertTrue(keys.add(item.get("pk").s()));
            });
    assertTrue(
        put.transactItems()
            .getFirst()
            .conditionCheck()
            .conditionExpression()
            .contains("readKeys = :read"));
    assertEquals(
        VaultItems.strings(STATE.readKeyIds()),
        put.transactItems().getFirst().conditionCheck().expressionAttributeValues().get(":read"));
    assertEquals(
        Duration.ofSeconds(2),
        put.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());
    assertEquals(
        Duration.ofSeconds(2),
        builder
            .references(SCOPE, hashes(SCOPE), TOKEN)
            .overrideConfiguration()
            .orElseThrow()
            .apiCallTimeout()
            .orElseThrow());
    assertTrue(builder.get(VaultItems.tokenKey(TOKEN)).consistentRead());
    assertThrows(IllegalArgumentException.class, () -> builder.insert(record(), STATE, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.insert(record(), STATE, List.of(hashes(SCOPE).getFirst())));
    assertThrows(
        IllegalArgumentException.class, () -> new VaultRequests("token-vault", Duration.ZERO));
  }

  @Test
  void deleteFencesRecordIncarnationAndEveryReferenceOwner() {
    var request = new VaultRequests("token-vault", Duration.ofSeconds(2)).delete(record());
    assertTrue(
        request
            .transactItems()
            .getFirst()
            .delete()
            .conditionExpression()
            .contains("expiresAt = :expires"));
    assertEquals(2, request.transactItems().size());
    assertTrue(
        request
            .transactItems()
            .getFirst()
            .delete()
            .conditionExpression()
            .contains("ciphertext = :ciphertext"));
    assertTrue(
        request
            .transactItems()
            .getLast()
            .delete()
            .conditionExpression()
            .contains("#token = :token"));
    assertEquals(
        TOKEN.reveal(),
        request.transactItems().getLast().delete().expressionAttributeValues().get(":token").s());
  }

  @Test
  void schemaRoundTripsButRejectsUnknownFieldsTypesOversizedOrMismatchedScope() {
    VaultRecord original = record();
    Map<String, AttributeValue> item = VaultItems.record(original);
    VaultRecord decoded = VaultItems.record(item);
    assertEquals(original.scope(), decoded.scope());
    assertEquals(original.fingerprints(), decoded.fingerprints());
    assertArrayEquals(original.ciphertext(), decoded.ciphertext());
    assertFalse(item.toString().contains("00883344"));
    assertFalse(item.containsKey("ttl"));
    var malformed = new HashMap<>(item);
    malformed.put("plaintext", VaultItems.s("synthetic"));
    assertThrows(VaultIntegrityException.class, () -> VaultItems.record(malformed));
    malformed.remove("plaintext");
    malformed.put("scope", VaultItems.s("another-scope"));
    assertThrows(VaultIntegrityException.class, () -> VaultItems.record(malformed));
    malformed.put("scope", item.get("scope"));
    malformed.put("canonicalVersion", VaultItems.s("1"));
    assertThrows(VaultIntegrityException.class, () -> VaultItems.record(malformed));
    assertEquals(STATE, VaultItems.control("main", VaultItems.control(STATE)));
    assertThrows(
        VaultIntegrityException.class,
        () -> VaultItems.control("other", VaultItems.control(STATE)));
  }

  @Test
  void indexAndRecordMustBelongToSameSerializableSnapshot() {
    var requests = new VaultRequests("token-vault", Duration.ofSeconds(2));
    var hashes = hashes(SCOPE);
    var indexes =
        List.of(item(VaultItems.fingerprint(record(), hashes.getFirst())), item(Map.of()));
    assertEquals(TOKEN, requests.owner(SCOPE, hashes, indexes, false).orElseThrow());
    var snapshot = new ArrayList<>(indexes);
    snapshot.add(item(VaultItems.record(record())));
    assertEquals(TOKEN, requests.association(SCOPE, hashes, TOKEN, snapshot).token());
    snapshot.set(2, item(Map.of()));
    assertThrows(
        VaultIntegrityException.class, () -> requests.association(SCOPE, hashes, TOKEN, snapshot));
    var foreign = new HashMap<>(VaultItems.fingerprint(record(), hashes.getLast()));
    foreign.put(
        "token",
        VaultItems.s(
            Token.fromEntropy(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})
                .reveal()));
    snapshot.set(1, item(foreign));
    assertThrows(
        VaultIntegrityException.class, () -> requests.owner(SCOPE, hashes, snapshot, true));
    var otherScope = new VaultScope(SCOPE.type(), "other", SCOPE.domain(), SCOPE.profileId(), 1);
    assertNotEquals(
        VaultItems.fingerprintKey(SCOPE, hashes.getFirst()),
        VaultItems.fingerprintKey(otherScope, hashes(otherScope).getFirst()));
  }
}

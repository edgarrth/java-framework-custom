package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.DNI;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.DOMAIN;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.TEXT;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.caller;
import static pe.axiz.core.tokenization.vault.VaultTokenizerTest.code;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import pe.axiz.core.tokenization.SensitiveValue;

class VaultAssociationIntegrityTest {
  @Test
  void syncRejectsCopiedFingerprintForTokenizeLookupAndReveal() {
    var f = new VaultTokenizerTest();
    try {
      var a = f.tokenize("SYNTHETIC-VALUE-A", TEXT, "one");
      var b = f.tokenize("SYNTHETIC-VALUE-B", TEXT, "one");
      substitute(f.store, f.store.records.get(a.token()), f.store.records.get(b.token()));
      int before = f.events.size();
      code("AXZ-TOKEN-0503", () -> f.tokenize("SYNTHETIC-VALUE-A", TEXT, "one"));
      try (var value = SensitiveValue.of("SYNTHETIC-VALUE-A")) {
        code(
            "AXZ-TOKEN-0503",
            () -> f.engine.lookup(caller("one"), TEXT, value, DOMAIN, "customer-support"));
      }
      code(
          "AXZ-TOKEN-0503",
          () -> f.engine.detokenize(caller("one"), TEXT, b.token(), DOMAIN, "customer-support"));
      code("AXZ-TOKEN-0503", () -> f.engine.describe(caller("one"), TEXT, b.token(), DOMAIN));
      assertTrue(
          f.events.subList(before, f.events.size()).stream()
              .allMatch(entry -> entry.result() == TokenAuditEntry.Result.INTEGRITY_FAILURE));
    } finally {
      f.close();
    }
  }

  @Test
  void asyncRejectsCopiedFingerprintForTokenizeLookupAndReveal() {
    var f = new AsyncVaultTokenizerTest();
    try {
      var a = f.tokenize("00883344", "one").join();
      var b = f.tokenize("00883345", "one").join();
      substitute(f.store, f.store.records.get(a.token()), f.store.records.get(b.token()));
      int before = f.events.size();
      AsyncVaultTokenizerTest.code("AXZ-TOKEN-0503", f.tokenize("00883344", "one"));
      try (var value = SensitiveValue.of("00883344")) {
        AsyncVaultTokenizerTest.code(
            "AXZ-TOKEN-0503",
            f.engine.lookup(caller("one"), DNI, value, DOMAIN, "customer-support"));
      }
      AsyncVaultTokenizerTest.code(
          "AXZ-TOKEN-0503",
          f.engine.detokenize(caller("one"), DNI, b.token(), DOMAIN, "customer-support"));
      AsyncVaultTokenizerTest.code(
          "AXZ-TOKEN-0503", f.engine.describe(caller("one"), DNI, b.token(), DOMAIN));
      assertTrue(
          f.events.subList(before, f.events.size()).stream()
              .allMatch(entry -> entry.result() == TokenAuditEntry.Result.INTEGRITY_FAILURE));
    } finally {
      f.close();
    }
  }

  @Test
  void syncCleanupFailurePreservesConfirmedResultAndBusinessAudit() {
    var f = new VaultTokenizerTest();
    try {
      f.releaseDown = true;
      var result = f.tokenize("00883344", DNI, "one");
      assertTrue(result.created());
      assertEquals(1, f.events.size());
      assertEquals(TokenAuditEntry.Result.CREATED, f.events.getFirst().result());
      try (var value =
          f.engine.detokenize(caller("one"), DNI, result.token(), DOMAIN, "customer-support")) {
        assertEquals("00883344", value.reveal());
      }
      assertEquals(TokenAuditEntry.Result.REVEALED, f.events.getLast().result());
      assertEquals(2, f.released.get());
    } finally {
      f.close();
    }
  }

  @Test
  void asyncCleanupFailurePreservesConfirmedResultAndBusinessAudit() {
    var f = new AsyncVaultTokenizerTest();
    try {
      f.pendingRelease = new CompletableFuture<>();
      var work = f.tokenize("00883344", "one");
      assertFalse(work.isDone());
      assertEquals(TokenAuditEntry.Result.CREATED, f.events.getFirst().result());
      f.pendingRelease.completeExceptionally(new IllegalStateException("synthetic cleanup"));
      var token = work.join().token();
      assertEquals(1, f.events.size());
      try (var value =
          f.engine
              .detokenize(caller("one"), DNI, token, DOMAIN, "customer-support")
              .toCompletableFuture()
              .join()) {
        assertEquals("00883344", value.reveal());
      }
      assertEquals(TokenAuditEntry.Result.REVEALED, f.events.getLast().result());
    } finally {
      f.close();
    }
  }

  private static void substitute(TestVault store, VaultRecord source, VaultRecord victim) {
    var forged =
        VaultRecord.builder()
            .token(victim.token())
            .scope(victim.scope())
            .keysetId(victim.keysetId())
            .fingerprints(source.fingerprints())
            .revision(victim.revision())
            .ciphertext(victim.ciphertext())
            .createdAt(victim.createdAt())
            .expiresAt(victim.expiresAt())
            .metadata(victim.metadata())
            .build();
    store.records.put(victim.token(), forged);
    for (var hash : source.fingerprints())
      store.references.put(new TestVault.Reference(source.scope(), hash), victim.token());
  }
}

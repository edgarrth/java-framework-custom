package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditVerificationBoundaryTest {
  private static final String CHAIN = "orders/audit";
  private static final String GENESIS = "0".repeat(64);
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

  private static AuditEvent fact() {
    return AuditSnapshotTest.event(Map.of("amount", 42));
  }

  @Test
  void emptyEvidenceDoesNotInventDeliveryAndCheckpointsBoundOnlyTheTrustedPrefix() {
    var state = new InMemoryAuditChainState(Clock.fixed(NOW, ZoneOffset.UTC), 2);
    var sealer = new AuditSealer(CHAIN, AuditSealer.Mode.HASH, state, null, null);
    var empty = AuditChainVerifier.verify(List.of(), null, null);
    assertEquals(AuditChainVerifier.Completeness.NOT_ASSESSED, empty.completeness());
    assertEquals(0, empty.upTo());
    assertEquals(0, empty.receivedThrough());
    var genesis = state.checkpoint(CHAIN);
    assertEquals(NOW, genesis.at());
    var verifiedEmpty = AuditChainVerifier.verify(List.of(), null, genesis);
    assertEquals(AuditChainVerifier.Completeness.COMPLETE_UP_TO, verifiedEmpty.completeness());
    assertSame(genesis, verifiedEmpty.checkpoint());
    var forgedGenesis =
        AuditChainVerifier.verify(
            List.of(), null, new ChainCheckpoint(CHAIN, 0, "f".repeat(64), NOW));
    assertEquals(AuditChainVerifier.IntegrityStatus.INVALID_HASH, forgedGenesis.integrity());
    assertEquals(0, forgedGenesis.upTo());
    assertEquals(AuditChainVerifier.Completeness.NOT_ASSESSED, forgedGenesis.completeness());
    var first = sealer.seal(fact());
    var checkpoint = state.checkpoint(CHAIN);
    sealer.seal(fact());
    var third = sealer.seal(fact());
    var laterGap = AuditChainVerifier.verify(List.of(first, third), null, checkpoint);
    assertEquals(AuditChainVerifier.IntegrityStatus.GAP, laterGap.integrity());
    assertEquals(AuditChainVerifier.Completeness.COMPLETE_UP_TO, laterGap.completeness());
    assertEquals(1, laterGap.upTo());
    assertEquals(3, laterGap.receivedThrough());
    assertThrows(UnsupportedOperationException.class, () -> laterGap.findings().clear());
    var absentHead = AuditChainVerifier.verify(List.of(third), null, checkpoint);
    assertEquals(0, absentHead.upTo());
    assertEquals(AuditChainVerifier.Completeness.NOT_ASSESSED, absentHead.completeness());
    assertEquals(3, sealer.sequence());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuditChainVerifier.verify(
                List.of(first),
                null,
                new ChainCheckpoint("another/chain", 1, first.integrity().hash(), NOW)));
  }

  @Test
  void tamperedFactsCannotAdvanceTheVerifiedPrefixEvenWithAValidStoredCheckpoint() {
    var state = new InMemoryAuditChainState(Clock.systemUTC(), 2);
    var sealer = new AuditSealer(CHAIN, AuditSealer.Mode.HASH, state, null, null);
    var original = sealer.seal(fact());
    var changed = AuditSnapshotTest.event(Map.of("amount", 99)).withIntegrity(original.integrity());
    var result = AuditChainVerifier.verify(List.of(changed), null, state.checkpoint(CHAIN));
    assertEquals(AuditChainVerifier.IntegrityStatus.INVALID_HASH, result.integrity());
    assertEquals(0, result.upTo());
    assertEquals(1, result.receivedThrough());
    assertEquals(AuditChainVerifier.Completeness.NOT_ASSESSED, result.completeness());
    assertThrows(
        NullPointerException.class, () -> AuditChainVerifier.verify(List.of(fact()), null, null));
  }

  @Test
  void invalidSuccessorsNeverAdvanceStateAndCapacityDoesNotEvictExistingChains() {
    var state = new InMemoryAuditChainState(Clock.systemUTC(), 1);
    var before = state.checkpoint(CHAIN);
    for (AuditIntegrity invalid :
        List.of(
            new AuditIntegrity("other", 1, GENESIS, "f".repeat(64), null, null),
            new AuditIntegrity(CHAIN, 2, GENESIS, "f".repeat(64), null, null),
            new AuditIntegrity(CHAIN, 1, "e".repeat(64), "f".repeat(64), null, null))) {
      assertThrows(IllegalArgumentException.class, () -> state.advance(CHAIN, old -> invalid));
      assertEquals(before.sequence(), state.checkpoint(CHAIN).sequence());
      assertEquals(before.hash(), state.checkpoint(CHAIN).hash());
    }
    assertThrows(IllegalStateException.class, () -> state.checkpoint("another"));
    var sealer = new AuditSealer(CHAIN, AuditSealer.Mode.HASH, state, null, null);
    sealer.seal(fact());
    assertEquals(1, state.checkpoint(CHAIN).sequence());
    assertThrows(
        IllegalArgumentException.class, () -> new InMemoryAuditChainState(Clock.systemUTC(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InMemoryAuditChainState(Clock.systemUTC(), 10001));
    assertEquals(
        0, new InMemoryAuditChainState(Clock.systemUTC(), 10000).checkpoint(CHAIN).sequence());
    var exhausted = AuditIntegrity.head(CHAIN, Long.MAX_VALUE, GENESIS);
    assertThrows(
        ArithmeticException.class, () -> exhausted.requireSuccessor(AuditIntegrity.initial(CHAIN)));
  }

  @Test
  void disabledAndHmacModesRejectAmbiguousKeyConfigurationAndNeverPretendToSeal() {
    var state = new InMemoryAuditChainState(Clock.systemUTC(), 2);
    var none = new AuditSealer(CHAIN, AuditSealer.Mode.NONE, state, null, null);
    var original = fact();
    assertSame(original, none.seal(original));
    assertEquals(0, none.sequence());
    var disabled = new AuditSealCalculator(AuditSealer.Mode.NONE, null, null);
    assertFalse(disabled.enabled());
    assertThrows(
        IllegalStateException.class,
        () -> disabled.next(original, AuditIntegrity.initial(CHAIN), null));
    assertTrue(new AuditSealCalculator(AuditSealer.Mode.HASH, null, null).enabled());
    for (AuditSealer.Mode mode : List.of(AuditSealer.Mode.NONE, AuditSealer.Mode.HASH)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AuditSealCalculator(mode, () -> new byte[32], null));
      assertThrows(IllegalArgumentException.class, () -> new AuditSealCalculator(mode, null, "k"));
    }
    assertThrows(
        NullPointerException.class,
        () -> new AuditSealCalculator(AuditSealer.Mode.HMAC, null, "k"));
    for (String invalid : List.of("", "bad.key", "k".repeat(65)))
      assertThrows(
          IllegalArgumentException.class,
          () -> new AuditSealCalculator(AuditSealer.Mode.HMAC, () -> new byte[32], invalid));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuditSealCalculator(AuditSealer.Mode.HMAC, () -> new byte[32], null));
  }
}

package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pe.axiz.core.commons.TransactionOutcome;

class AuditIntegrityTest {
  @Test
  void wireRoundTripPreservesSealsAndRejectsLossyOrAmbiguousInputs() {
    var sealer = hash(state());
    var received = new ArrayList<AuditEvent>();
    for (Object number :
        List.of(
            0.1f,
            -0.0d,
            Double.MAX_VALUE,
            Double.MIN_VALUE,
            new BigDecimal("10.50"),
            new BigDecimal("1E+64"),
            BigInteger.ONE.shiftLeft(255))) {
      var sealed = sealer.seal(AuditSnapshotTest.event(Map.of("value", number)));
      String json = AuditEventJson.write(sealed);
      var decoded = AuditEventJson.read(json);
      assertEquals(json, AuditEventJson.write(decoded));
      received.add(decoded);
    }
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INTACT,
        AuditChainVerifier.verify(received, null, null).integrity());
    String valid = AuditEventJson.write(event());
    for (String malformed :
        List.of(
            valid.replace("\"id\":", "\"surprise\":true,\"id\":"),
            valid.replace("\"id\":", "\"id\":\"duplicate\",\"id\":"),
            valid.replace("1970-01-01T00:00:00Z", "secret-date-value"),
            valid.replace("\"amount\":42", "\"amount\":1E9999"),
            valid.replace("\"amount\":42", "\"" + "x".repeat(129) + "\":42"))) {
      var error =
          assertThrows(IllegalArgumentException.class, () -> AuditEventJson.read(malformed));
      assertEquals("Invalid audit JSON", error.getMessage());
      assertNull(error.getCause());
    }
  }

  private static final String CHAIN = "svc/sync/instance-1";

  private static byte[] key() {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 17);
    return key;
  }

  private static AuditEvent event() {
    return AuditSnapshotTest.event(Map.of("amount", 42));
  }

  private static InMemoryAuditChainState state() {
    return new InMemoryAuditChainState(Clock.systemUTC(), 8);
  }

  private static AuditSealer hash(InMemoryAuditChainState state) {
    return new AuditSealer(CHAIN, AuditSealer.Mode.HASH, state, null, null);
  }

  @Test
  void sealsMatchIndependentPythonFramingAndDigestVectors() {
    var plain = hash(state()).seal(event());
    assertEquals(
        "35f5f3dd4ba59fac935848e78ff7aeb6b35ab45384e87356a00b27a3947a2f3f",
        plain.integrity().hash());
    byte[] transferred = key();
    var mac =
        new AuditSealer(CHAIN, AuditSealer.Mode.HMAC, state(), () -> transferred, "k1")
            .seal(event());
    assertEquals(
        "24cec8f97b57d7b932ad277c2575de833b41ddca6b60d35416ddd78d2b7d77a6", mac.integrity().hash());
    assertArrayEquals(new byte[32], transferred);
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INTACT,
        AuditChainVerifier.verify(List.of(mac), key(), null).integrity());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INVALID_HASH,
        AuditChainVerifier.verify(List.of(mac), new byte[32], null).integrity());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INVALID_HASH,
        AuditChainVerifier.verify(List.of(mac), null, null).integrity());
  }

  @Test
  void sealedFactCannotChangeOutcomesButTransportMetadataMayChange() {
    var original = event();
    var sealer = hash(state());
    var sealed = sealer.seal(original);
    assertNull(original.integrity());
    assertThrows(IllegalStateException.class, () -> sealer.seal(sealed));
    assertThrows(
        IllegalStateException.class,
        () -> sealed.withOutcomes(TransactionOutcome.COMMITTED, TransactionOutcome.COMMITTED));
    var fallback = sealed.withPersistence("NOT_CONFIRMED");
    assertSame(sealed.integrity(), fallback.integrity());
    assertSame(sealed.details(), fallback.details());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INTACT,
        AuditChainVerifier.verify(List.of(fallback), null, null).integrity());
    assertEquals("1", sealed.cloudEventAttributes().get("axizseq"));
    assertEquals(CHAIN, sealed.cloudEventAttributes().get("axizchain"));
  }

  @Test
  @Timeout(30)
  void concurrentCalculationAndOutOfOrderDeliveryStillFormOneChain() throws Exception {
    var state = state();
    var sealer = hash(state);
    var received = new ConcurrentLinkedQueue<AuditEvent>();
    var original = event();
    try (var executor = Executors.newFixedThreadPool(32)) {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 10000; i++)
        tasks.add(executor.submit(() -> received.add(sealer.seal(original))));
      for (var task : tasks) task.get();
    }
    var shuffled = new ArrayList<>(received);
    Collections.shuffle(shuffled, new Random(17));
    var result = AuditChainVerifier.verify(shuffled, null, state.checkpoint(CHAIN));
    assertEquals(AuditChainVerifier.IntegrityStatus.INTACT, result.integrity());
    assertEquals(10000, result.upTo());
    assertEquals(AuditChainVerifier.Completeness.COMPLETE_UP_TO, result.completeness());
    // Este checkpoint establece coincidencia con la cabeza en memoria, no entrega durable.
  }

  @Test
  void reportsGapsConflictsTamperingAndMissingTailWithoutInventingCompleteness() {
    var state = state();
    var sealer = hash(state);
    var first = sealer.seal(event());
    var second = sealer.seal(event());
    var third = sealer.seal(event());
    var gap = AuditChainVerifier.verify(List.of(third, first), null, null);
    assertEquals(AuditChainVerifier.IntegrityStatus.GAP, gap.integrity());
    assertEquals(
        List.of(new AuditChainVerifier.Finding(AuditChainVerifier.IntegrityStatus.GAP, 2, 2)),
        gap.findings());
    assertEquals(1, gap.upTo());
    assertEquals(AuditChainVerifier.Completeness.NOT_ASSESSED, gap.completeness());
    assertEquals(
        AuditChainVerifier.Completeness.NOT_ASSESSED,
        AuditChainVerifier.verify(List.of(first), null, null).completeness());
    assertEquals(
        AuditChainVerifier.Completeness.TAIL_MISSING,
        AuditChainVerifier.verify(List.of(first), null, state.checkpoint(CHAIN)).completeness());
    var duplicate =
        AuditChainVerifier.verify(
            List.of(first, first, third, second), null, state.checkpoint(CHAIN));
    assertEquals(AuditChainVerifier.IntegrityStatus.INTACT, duplicate.integrity());
    assertEquals(AuditChainVerifier.Completeness.COMPLETE_UP_TO, duplicate.completeness());
    var changed = AuditSnapshotTest.event(Map.of("amount", 99)).withIntegrity(first.integrity());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INVALID_HASH,
        AuditChainVerifier.verify(List.of(first, changed), null, null).integrity());
    var forgedPosition = new AuditIntegrity(CHAIN, 1, "0".repeat(64), "f".repeat(64), null, null);
    var conflict = event().withIntegrity(forgedPosition);
    assertEquals(
        AuditChainVerifier.IntegrityStatus.CONFLICT,
        AuditChainVerifier.verify(List.of(first, conflict), null, null).integrity());
    var wrongCheckpoint = new ChainCheckpoint(CHAIN, 3, "e".repeat(64), Instant.EPOCH);
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INVALID_HASH,
        AuditChainVerifier.verify(List.of(first, second, third), null, wrongCheckpoint)
            .integrity());
  }

  @Test
  void everyPreviousAttemptFieldAndItsPresenceAreAuthenticated() {
    var first = hash(state()).seal(event());
    var position = first.integrity();
    var previous =
        new AttemptRef(
            position.chainId(), position.sequence(), position.hash(), AttemptOutcome.UNKNOWN);
    var fallback =
        new AuditSealer(
            "svc/degraded/instance-1",
            AuditSealer.Mode.HMAC,
            state(),
            AuditIntegrityTest::key,
            "k1");
    var retried = fallback.sealAgain(event(), previous);
    assertEquals(first.id(), retried.id());
    assertNotEquals(first.deliveryId(), retried.deliveryId());
    assertEquals(first.deliveryId(), first.withPersistence("UNCERTAIN").deliveryId());
    assertEquals(retried.deliveryId(), retried.cloudEventAttributes().get("id"));
    assertNotEquals(first.integrity().chainId(), retried.integrity().chainId());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INTACT,
        AuditChainVerifier.verify(List.of(retried), key(), null).integrity());
    var alternatives = new ArrayList<AttemptRef>();
    alternatives.add(null);
    alternatives.add(new AttemptRef("other", 1, previous.hash(), AttemptOutcome.UNKNOWN));
    alternatives.add(
        new AttemptRef(previous.chainId(), 2, previous.hash(), AttemptOutcome.UNKNOWN));
    alternatives.add(new AttemptRef(previous.chainId(), 1, "f".repeat(64), AttemptOutcome.UNKNOWN));
    alternatives.add(
        new AttemptRef(previous.chainId(), 1, previous.hash(), AttemptOutcome.ROLLED_BACK));
    var seal = retried.integrity();
    for (var altered : alternatives) {
      var changed =
          event()
              .withIntegrity(
                  new AuditIntegrity(
                      seal.chainId(),
                      seal.sequence(),
                      seal.previousHash(),
                      seal.hash(),
                      seal.keyId(),
                      altered));
      assertEquals(
          AuditChainVerifier.IntegrityStatus.INVALID_HASH,
          AuditChainVerifier.verify(List.of(changed), key(), null).integrity());
    }
  }

  @Test
  void failedCalculationDoesNotAdvanceAndDecoratorClosesItsDelegateOnce() {
    var state = state();
    var badKey = new AuditSealer(CHAIN, AuditSealer.Mode.HMAC, state, () -> new byte[8], "short");
    assertThrows(IllegalArgumentException.class, () -> badKey.seal(event()));
    assertEquals(0, state.checkpoint(CHAIN).sequence());
    assertThrows(
        IllegalStateException.class,
        () ->
            state.advance(
                CHAIN,
                head -> {
                  throw new IllegalStateException();
                }));
    assertEquals(0, state.checkpoint(CHAIN).sequence());
    var closes = new AtomicInteger();
    var events = new ArrayList<AuditEvent>();
    class Destination implements AuditPublisher, AutoCloseable {
      @Override
      public void publish(AuditEvent event) {
        events.add(event);
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    }
    var destination = new Destination();
    var publisher = new SealingAuditPublisher(hash(state), destination);
    publisher.publish(event());
    assertSame(destination, publisher.delegate());
    publisher.close();
    publisher.close();
    assertEquals(1, closes.get());
    assertTrue(events.getFirst().integrity() != null);
    assertThrows(IllegalStateException.class, () -> publisher.publish(event()));
  }
}

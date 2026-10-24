package pe.axiz.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import pe.axiz.core.security.RequestSignature.Message;
import pe.axiz.core.security.RequestSignature.Outcome;

/** Signed callbacks: one signed string per request, accepted once, within the window. */
class RequestSignatureTest {

  private static final byte[] K1 =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] K2 =
      "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
  private static final String NONCE = "n0nce-0000000001";

  private final SecurityTest.MutableClock clock = new SecurityTest.MutableClock();

  private RequestSignature signer(String active, NonceStore nonces) {
    return new RequestSignature(
        Map.of("k1", K1, "k2", K2), active, Duration.ofMinutes(5), clock, nonces);
  }

  private RequestSignature signer() {
    return signer("k1", new InMemoryNonceStore(1000, clock));
  }

  private Message message(String nonce, String method, String target, String body) {
    return Message.of(clock.now, nonce, method, target, body);
  }

  @Test
  void anAuthenticMessageIsAcceptedOnceAndItsReplayIsRejected() {
    RequestSignature s = signer();
    Message m = message(NONCE, "POST", "/v1/callbacks?x=1", "{\"a\":1}");
    String header = s.sign(m);
    assertTrue(header.matches("keyId=k1,v2=[0-9a-f]{64}"), header);
    assertEquals(Outcome.VALID, s.verify(m, header));
    assertEquals(Outcome.REPLAYED, s.verify(m, header), "same nonce inside the window");
    clock.now = clock.now.plusSeconds(200);
    assertEquals(Outcome.REPLAYED, s.verify(m, header), "still remembered while it could be valid");
  }

  @Test
  void fieldBoundariesCannotBeShifted() {
    RequestSignature s = signer();
    // the same characters split differently between target and body (and method and target)
    String a = s.sign(message(NONCE, "POST", "/callback.a", "b"));
    String b = s.sign(message(NONCE, "POST", "/callback", "a.b"));
    assertNotEquals(a, b);
    assertEquals(
        Outcome.BAD_SIGNATURE, signer().verify(message(NONCE, "POST", "/callback", "a.b"), a));
    String canonical = RequestSignature.canonical("k1", message(NONCE, "POST", "/x", ""));
    assertEquals(
        "AXIZ-HMAC-V2\nk1\n1772359200\n"
            + NONCE
            + "\nPOST\n/x\n"
            + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        canonical,
        "one field per line; the body as its SHA-256");
  }

  @Test
  void everyComponentIsCovered() {
    Message base = message(NONCE, "POST", "/v1/pay?a=1&b=2", "{\"amount\":\"10.00\"}");
    String header = signer().sign(base);
    List<Message> changed = new ArrayList<>();
    changed.add(
        Message.of(
            clock.now.plusSeconds(1), NONCE, "POST", "/v1/pay?a=1&b=2", "{\"amount\":\"10.00\"}"));
    changed.add(message("n0nce-0000000002", "POST", "/v1/pay?a=1&b=2", "{\"amount\":\"10.00\"}"));
    changed.add(message(NONCE, "PUT", "/v1/pay?a=1&b=2", "{\"amount\":\"10.00\"}"));
    changed.add(message(NONCE, "POST", "/v1/pay?b=2&a=1", "{\"amount\":\"10.00\"}"));
    changed.add(message(NONCE, "POST", "/v1/pay%3Fa=1&b=2", "{\"amount\":\"10.00\"}"));
    changed.add(message(NONCE, "POST", "/v1/pay?a=1&b=2", "{\"amount\":\"10.01\"}"));
    changed.add(message(NONCE, "POST", "/v1/pay?a=1&b=2", "{\"amount\":\"10.00\"} "));
    Set<String> seen = new HashSet<>();
    for (Message m : changed) {
      assertEquals(Outcome.BAD_SIGNATURE, signer().verify(m, header), m.toString());
      assertTrue(seen.add(signer().sign(m)), "distinct signature for " + m);
    }
    byte[] body = base.body();
    for (int bit = 0; bit < 8; bit++) {
      byte[] flipped = body.clone();
      flipped[0] ^= (byte) (1 << bit);
      Message m = new Message(base.timestamp(), NONCE, "POST", base.target(), flipped);
      assertEquals(Outcome.BAD_SIGNATURE, signer().verify(m, header));
    }
    assertEquals(Outcome.VALID, signer().verify(base, header));
  }

  @Test
  void anEmptyBodyAndNoBodyAreTheSame() {
    Message empty = message(NONCE, "GET", "/v1/status", "");
    Message none = new Message(clock.now, NONCE, "GET", "/v1/status", new byte[0]);
    assertEquals(signer().sign(empty), signer().sign(none));
    assertEquals(empty, none);
    assertEquals(empty.hashCode(), none.hashCode());
  }

  @Test
  void theWindowIsEnforcedBothWays() {
    RequestSignature s = signer();
    Message m = message(NONCE, "POST", "/cb", "x");
    String header = s.sign(m);
    clock.now = clock.now.plus(Duration.ofMinutes(6));
    assertEquals(Outcome.STALE, s.verify(m, header));
    clock.now = clock.now.minus(Duration.ofMinutes(12));
    assertEquals(Outcome.STALE, s.verify(m, header), "a timestamp from the future");
  }

  @Test
  void keysRotateByKeyId() {
    Message m = message(NONCE, "POST", "/cb", "x");
    String old = signer("k1", new InMemoryNonceStore(10, clock)).sign(m);
    RequestSignature rotated = signer("k2", new InMemoryNonceStore(10, clock));
    assertTrue(rotated.sign(m).startsWith("keyId=k2,"));
    assertEquals(Outcome.VALID, rotated.verify(m, old), "the previous key still verifies");
    RequestSignature onlyNew =
        new RequestSignature(
            Map.of("k2", K2),
            "k2",
            Duration.ofMinutes(5),
            clock,
            new InMemoryNonceStore(10, clock));
    assertEquals(Outcome.UNKNOWN_KEY, onlyNew.verify(m, old));
    String forged = old.replace("keyId=k1", "keyId=k2");
    assertEquals(
        Outcome.BAD_SIGNATURE,
        rotated.verify(message("n0nce-0000000009", "POST", "/cb", "x"), forged));
  }

  @Test
  void malformedInputIsRejectedBeforeAnyCryptography() {
    RequestSignature s = signer();
    Message m = message(NONCE, "POST", "/cb", "x");
    String good = s.sign(m);
    for (String header :
        new String[] {
          null,
          "",
          "v2=" + good.substring(11),
          good.toUpperCase(),
          good + ",",
          "keyId=k1,v1=" + good.substring(12)
        }) {
      assertEquals(Outcome.MALFORMED, s.verify(m, header), String.valueOf(header));
    }
    String[][] bad = {
      {"short", "POST", "/cb"},
      {"n0nce\n000000000001", "POST", "/cb"},
      {NONCE, "post", "/cb"},
      {NONCE, "PO ST", "/cb"},
      {NONCE, "POST", "cb"},
      {NONCE, "POST", ""},
      {NONCE, "POST", "/c b"},
      {NONCE, "POST", "/cb\n"},
      {NONCE, "POST", "/café"},
      {NONCE, "POST", "/" + "a".repeat(RequestSignature.MAX_TARGET)},
    };
    for (String[] f : bad) {
      Message wrong = message(f[0], f[1], f[2], "x");
      assertThrows(IllegalArgumentException.class, () -> s.sign(wrong), String.join("|", f));
      assertEquals(Outcome.MALFORMED, s.verify(wrong, good));
    }
  }

  @Test
  void aStoreThatCannotAnswerFailsClosedAndForgeriesNeverConsumeNonces() {
    NonceStore down =
        (nonce, ttl) -> {
          throw new IllegalStateException("down");
        };
    Message m = message(NONCE, "POST", "/cb", "x");
    String header = signer().sign(m);
    assertEquals(Outcome.REPLAY_CHECK_UNAVAILABLE, signer("k1", down).verify(m, header));
    assertFalse(Outcome.REPLAY_CHECK_UNAVAILABLE.valid());

    Map<String, Duration> marked = new ConcurrentHashMap<>();
    NonceStore spy = (nonce, ttl) -> marked.put(nonce, ttl) == null;
    RequestSignature s = signer("k1", spy);
    String forged = "keyId=k1,v2=" + "0".repeat(64);
    assertEquals(Outcome.BAD_SIGNATURE, s.verify(m, forged));
    assertTrue(marked.isEmpty(), "a forgery does not burn the nonce of the real message");
    assertEquals(Outcome.VALID, s.verify(m, header));
    assertEquals(Map.of("k1:" + NONCE, Duration.ofMinutes(10)), marked);
  }

  @Test
  void configurationIsValidated() {
    InMemoryNonceStore n = new InMemoryNonceStore(10, clock);
    Duration t = Duration.ofMinutes(5);
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestSignature(Map.of("k1", new byte[8]), "k1", t, clock, n));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestSignature(Map.of("k 1", K1), "k 1", t, clock, n));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestSignature(Map.of("k1", K1), "k2", t, clock, n));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestSignature(Map.of("k1", K1), "k1", Duration.ZERO, clock, n));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestSignature(Map.of("k1", K1), "k1", Duration.ofMinutes(16), clock, n));
    assertThrows(IllegalArgumentException.class, () -> new InMemoryNonceStore(0, clock));
    byte[] key = K1.clone();
    RequestSignature s = new RequestSignature(Map.of("k1", key), "k1", t, clock, n);
    Message m = message(NONCE, "POST", "/cb", "x");
    String before = s.sign(m);
    key[0] = 'X';
    assertEquals(before, s.sign(m), "the signer keeps its own copy of the key");
    assertTrue(RequestSignature.newNonce().matches("[A-Za-z0-9_-]{22}"));
    assertNotEquals(RequestSignature.newNonce(), RequestSignature.newNonce());
  }

  @Test
  void theInMemoryStoreIsAtomicBoundedAndForgetsExpiredNonces() throws Exception {
    InMemoryNonceStore store = new InMemoryNonceStore(2, clock);
    assertTrue(store.markUsed("a", Duration.ofSeconds(10)));
    assertFalse(store.markUsed("a", Duration.ofSeconds(10)));
    assertTrue(store.markUsed("b", Duration.ofSeconds(10)));
    assertThrows(
        IllegalStateException.class,
        () -> store.markUsed("c", Duration.ofSeconds(10)),
        "full: fail closed");
    clock.now = clock.now.plusSeconds(11);
    assertTrue(store.markUsed("c", Duration.ofSeconds(10)), "expired entries were swept");
    assertTrue(store.markUsed("a", Duration.ofSeconds(10)), "an expired nonce may be used again");

    InMemoryNonceStore shared = new InMemoryNonceStore(10, clock);
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return shared.markUsed("same", Duration.ofMinutes(1));
                }));
      }
      start.countDown();
      int firsts = 0;
      for (Future<Boolean> f : results) {
        firsts += f.get() ? 1 : 0;
      }
      assertEquals(1, firsts, "exactly one concurrent caller wins");
    } finally {
      pool.shutdownNow();
    }
  }
}

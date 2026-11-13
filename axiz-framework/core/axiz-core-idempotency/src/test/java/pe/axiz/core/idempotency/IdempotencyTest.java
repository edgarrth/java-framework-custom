package pe.axiz.core.idempotency;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class IdempotencyTest {

  private final IdempotencyService service =
      new IdempotencyService(
          new InMemoryIdempotencyStore(),
          Duration.ofMinutes(1),
          Duration.ofHours(24),
          Clock.systemUTC());
  private final String key = "idem-" + java.util.UUID.randomUUID();
  private final byte[] body = "{\"amount\":\"10.00\"}".getBytes(StandardCharsets.UTF_8);

  @Test
  void firstRequestProceedsAndRepeatsReplay() {
    String fp = IdempotencyService.fingerprint("POST", "/v1/payments", body);
    IdempotencyService.Decision first = service.begin("app:create-payment", key, fp);
    IdempotencyService.Proceed proceed = assertInstanceOf(IdempotencyService.Proceed.class, first);
    BusinessException concurrent =
        assertThrows(BusinessException.class, () -> service.begin("app:create-payment", key, fp));
    assertEquals(409, concurrent.category().httpStatus());
    service.complete(
        proceed,
        new StoredResponse(
            201,
            Map.of("Location", "/v1/payments/1"),
            "{\"id\":1}".getBytes(StandardCharsets.UTF_8)));
    IdempotencyService.Replay replay =
        assertInstanceOf(
            IdempotencyService.Replay.class, service.begin("app:create-payment", key, fp));
    assertEquals(201, replay.response().status());
    assertEquals("/v1/payments/1", replay.response().headers().get("Location"));
  }

  @Test
  void sameKeyDifferentRequestIs422AndOtherScopesAreIndependent() {
    String fp = IdempotencyService.fingerprint("POST", "/v1/payments", body);
    service.begin("app:create-payment", key, fp);
    String other =
        IdempotencyService.fingerprint(
            "POST", "/v1/payments", "{\"amount\":\"99\"}".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        422,
        assertThrows(BusinessException.class, () -> service.begin("app:create-payment", key, other))
            .category()
            .httpStatus());
    assertInstanceOf(
        IdempotencyService.Proceed.class, service.begin("other-app:create-payment", key, fp));
    assertNotEquals(fp, other);
  }

  @Test
  void failureReleasesTheKey() {
    String fp = IdempotencyService.fingerprint("POST", "/v1/payments", body);
    IdempotencyService.Proceed p = (IdempotencyService.Proceed) service.begin("s", key, fp);
    service.releaseWithoutEffect(p);
    assertInstanceOf(IdempotencyService.Proceed.class, service.begin("s", key, fp));
  }

  @Test
  void missingOrInvalidKeyIs400() {
    assertEquals(
        400,
        assertThrows(BusinessException.class, () -> service.begin("s", null, "fp"))
            .category()
            .httpStatus());
    assertEquals(
        400,
        assertThrows(BusinessException.class, () -> service.begin("s", "short", "fp"))
            .category()
            .httpStatus());
  }

  /** Mutable clock for lock-expiry scenarios. */
  static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /**
   * Regression: request A holds the key, its lock expires, request B takes the key and is still
   * running when A finishes. A's response must not be stored over B, nor may A release B's key.
   */
  @Test
  void aLateCompletionCannotOverwriteTheExecutionThatTookTheKey() {
    TestClock clock = new TestClock();
    IdempotencyService svc =
        new IdempotencyService(
            new InMemoryIdempotencyStore(clock),
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            clock);
    String fp = IdempotencyService.fingerprint("POST", "/v1/payments", body);
    IdempotencyService.Proceed a =
        assertInstanceOf(IdempotencyService.Proceed.class, svc.begin("s", key, fp));
    clock.now = clock.now.plus(Duration.ofHours(2));
    IdempotencyService.Proceed b =
        assertInstanceOf(IdempotencyService.Proceed.class, svc.begin("s", key, fp));
    assertNotEquals(a.owner(), b.owner());

    assertFalse(svc.complete(a, new StoredResponse(201, Map.of(), "A".getBytes(UTF_8))));
    assertFalse(svc.fail(a));
    assertEquals(
        409,
        assertThrows(BusinessException.class, () -> svc.begin("s", key, fp))
            .category()
            .httpStatus());

    assertTrue(svc.complete(b, new StoredResponse(201, Map.of(), "B".getBytes(UTF_8))));
    IdempotencyService.Replay replay =
        assertInstanceOf(IdempotencyService.Replay.class, svc.begin("s", key, fp));
    assertArrayEquals("B".getBytes(UTF_8), replay.response().body());
  }

  @Test
  void theInMemoryStoreExpiresWithTheServiceClock() {
    TestClock clock = new TestClock();
    clock.now = Instant.parse("2000-01-01T00:00:00Z"); // far from the system clock
    IdempotencyService svc =
        new IdempotencyService(
            new InMemoryIdempotencyStore(clock),
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            clock);
    String fp = IdempotencyService.fingerprint("POST", "/p", body);
    svc.begin("s", key, fp);
    clock.now = clock.now.plus(Duration.ofHours(2));
    assertInstanceOf(IdempotencyService.Proceed.class, svc.begin("s", key, fp));
  }

  /**
   * Regression: with U+0000 U+0002 inside a part, ("app" + S + "other", "subject") and ("app",
   * "other" + S + "subject") hashed the same bytes. Parts with control characters are rejected
   * before anything starts, which keeps the encoding injective without changing the scope of clean
   * callers (requests in flight across an upgrade keep their keys).
   */
  @Test
  void aPartCannotImitateTheSeparatorsOfTheScope() {
    String s = "\u0000\u0002";
    assertThrows(
        IllegalArgumentException.class,
        () -> IdempotencyService.scope("op", "iss", "t", "app" + s + "other", "subject"));
    assertThrows(
        IllegalArgumentException.class,
        () -> IdempotencyService.scope("op", "iss", "t", "app", "other" + s + "subject"));
    for (String control : new String[] {"\u0000", "\u0001", "\t", "\n", "\u001f", "\u007f"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> IdempotencyService.scope("op", "iss" + control, null, null, null),
          control);
    }
    // the hash of a clean caller is the one earlier releases computed
    assertEquals(
        "op:1762d179852eb97b8e8bd49538404fea2d21f5d01eee4d990616230cab44fc28",
        IdempotencyService.scope("op", "iss", "t1", "app", "user-1"));
    // empty and absent parts, and non-ASCII text, stay distinct and accepted
    assertNotEquals(
        IdempotencyService.scope("op", "iss", "", "app", null),
        IdempotencyService.scope("op", "iss", null, "app", ""));
    assertNotEquals(
        IdempotencyService.scope("op", "iss", null, "app", "josé"),
        IdempotencyService.scope("op", "iss", null, "app", "jose"));
  }

  @Test
  void scopeRejectsMalformedUnicodeWithoutChangingValidIdentities() {
    for (String invalid : new String[] {"app\uD800", "app\uDC00", "\uD800x", "\uD800\uD800"}) {
      for (int position = 0; position < 4; position++) {
        String[] identity = {"iss", "bank", "app", "user"};
        identity[position] = invalid;
        assertThrows(
            IllegalArgumentException.class,
            () ->
                IdempotencyService.scope("op", identity[0], identity[1], identity[2], identity[3]));
      }
    }
    assertNotEquals(
        IdempotencyService.scope("op", "iss", "bank", "app\uD83D\uDE00", "user"),
        IdempotencyService.scope("op", "iss", "bank", "app?", "user"));
    assertEquals(
        "op:1762d179852eb97b8e8bd49538404fea2d21f5d01eee4d990616230cab44fc28",
        IdempotencyService.scope("op", "iss", "t1", "app", "user-1"));
  }

  /** Regression: the scope separates issuers, tenants, clients and subjects. */
  @Test
  void theScopeSeparatesEveryCallerDimension() {
    String base = IdempotencyService.scope("op", "iss", "t1", "app", "user-1");
    assertEquals(base, IdempotencyService.scope("op", "iss", "t1", "app", "user-1"));
    assertTrue(base.startsWith("op:"));
    for (String other :
        new String[] {
          IdempotencyService.scope("op", "other-iss", "t1", "app", "user-1"),
          IdempotencyService.scope("op", "iss", "t2", "app", "user-1"),
          IdempotencyService.scope("op", "iss", "t1", "app-2", "user-1"),
          IdempotencyService.scope("op", "iss", "t1", "app", "user-2"),
          IdempotencyService.scope("op", "iss", "t1", "app", null),
          IdempotencyService.scope("op", "iss", "t1", "app", ""),
          IdempotencyService.scope("op2", "iss", "t1", "app", "user-1")
        }) {
      assertNotEquals(base, other);
    }
    assertNotEquals(
        IdempotencyService.scope("op", "a", "b", null, null),
        IdempotencyService.scope("op", "ab", null, null, null));
    String fp = IdempotencyService.fingerprint("POST", "/p", body);
    String userOne = IdempotencyService.scope("op", "iss", "t1", "app", "user-1");
    String userTwo = IdempotencyService.scope("op", "iss", "t1", "app", "user-2");
    IdempotencyService.Proceed p =
        assertInstanceOf(IdempotencyService.Proceed.class, service.begin(userOne, key, fp));
    service.complete(p, new StoredResponse(200, Map.of(), "secret".getBytes(UTF_8)));
    assertInstanceOf(IdempotencyService.Proceed.class, service.begin(userTwo, key, fp));
  }

  /**
   * Regressions: the query string is part of the request identity, and is taken as sent, so an
   * order-sensitive repeated parameter ({@code sort=a&sort=b}) is not confused with its reverse.
   */
  @Test
  void theFingerprintIncludesTheQueryStringAsSent() {
    String a = IdempotencyService.fingerprint("POST", "/v1/transfers", "amount=10&to=1", body);
    assertNotEquals(
        a, IdempotencyService.fingerprint("POST", "/v1/transfers", "amount=99&to=1", body));
    assertNotEquals(
        IdempotencyService.fingerprint("POST", "/v1/report", "sort=a&sort=b", body),
        IdempotencyService.fingerprint("POST", "/v1/report", "sort=b&sort=a", body));
    assertEquals(
        a, IdempotencyService.fingerprint("POST", "/v1/transfers", "amount=10&to=1", body));
    assertNotEquals(a, IdempotencyService.fingerprint("POST", "/v1/transfers", null, body));
    assertEquals(
        IdempotencyService.fingerprint("POST", "/p", body),
        IdempotencyService.fingerprint("POST", "/p", "", body));
  }

  @Test
  void anOversizedResponseKeepsStatusAndLocationButNotTheBody() {
    StoredResponse r =
        StoredResponse.withoutBody(
            201, Map.of("Location", "/v1/payments/9", "Content-Type", "application/json"));
    assertTrue(r.bodyOmitted());
    assertEquals(0, r.body().length);
    assertEquals("/v1/payments/9", r.headers().get("Location"));
    assertFalse(r.headers().containsKey("Content-Type"));
    assertFalse(new StoredResponse(200, Map.of(), new byte[0]).bodyOmitted());
  }

  @Test
  void codecRoundTrip() {
    IdempotencyRecord r =
        new IdempotencyRecord(
            "k",
            "fp",
            IdempotencyRecord.Status.COMPLETED,
            "owner-1",
            new StoredResponse(200, Map.of("Content-Type", "application/json"), body),
            Instant.parse("2026-01-01T00:00:00Z"));
    IdempotencyRecord back = StoredResponseCodec.read("k", StoredResponseCodec.write(r));
    assertEquals(r.fingerprint(), back.fingerprint());
    assertEquals(r.status(), back.status());
    assertEquals("owner-1", back.owner());
    assertEquals(r.expiresAt(), back.expiresAt());
    assertArrayEquals(body, back.response().body());
    assertEquals("application/json", back.response().headers().get("Content-Type"));
    IdempotencyRecord pending =
        new IdempotencyRecord(
            "k", "fp", IdempotencyRecord.Status.IN_PROGRESS, null, null, Instant.EPOCH);
    assertEquals(
        null, StoredResponseCodec.read("k", StoredResponseCodec.write(pending)).response());
    assertEquals("x", StoredResponseCodec.utf8("x".getBytes(StandardCharsets.UTF_8)));
  }
}

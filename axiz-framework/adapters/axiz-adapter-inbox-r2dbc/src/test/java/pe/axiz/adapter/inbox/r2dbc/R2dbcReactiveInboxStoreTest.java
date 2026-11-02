package pe.axiz.adapter.inbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.RowMapper;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class R2dbcReactiveInboxStoreTest {
  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

  @Test
  void defersSubmissionPreservesContextAndCancelsTheActiveStatement() {
    var calls = new AtomicInteger();
    var cancelled = new AtomicBoolean();
    ReactiveSql sql =
        new ReactiveSql() {
          public Mono<Long> update(String statement, SqlParams params) {
            calls.incrementAndGet();
            return Mono.deferContextual(
                context -> {
                  assertEquals("request-1", context.get("request"));
                  return Mono.<Long>never().doOnCancel(() -> cancelled.set(true));
                });
          }

          public <T> Flux<T> query(String statement, SqlParams params, RowMapper<T> mapper) {
            return Flux.empty();
          }
        };
    var store = new R2dbcReactiveInboxStore(sql, "axiz_inbox");
    var pending =
        store.tryAcquire("c", "m", "owner", NOW, NOW.plusSeconds(10), NOW.plusSeconds(100));
    assertEquals(0, calls.get());
    StepVerifier.create(pending.contextWrite(c -> c.put("request", "request-1")))
        .then(() -> assertEquals(1, calls.get()))
        .thenCancel()
        .verify();
    assertTrue(cancelled.get());
  }

  @Test
  void cancellingTheStatusLookupDoesNotAcquireOrCompleteTheRegistration() {
    var cancelled = new AtomicBoolean();
    ReactiveSql sql =
        new ReactiveSql() {
          public Mono<Long> update(String statement, SqlParams params) {
            return Mono.just(0L);
          }

          public <T> Flux<T> query(String statement, SqlParams params, RowMapper<T> mapper) {
            return Flux.<T>never().doOnCancel(() -> cancelled.set(true));
          }
        };
    var store = new R2dbcReactiveInboxStore(sql, "axiz_inbox");
    StepVerifier.create(
            store.tryAcquire("c", "m", "o", NOW, NOW.plusSeconds(10), NOW.plusSeconds(100)))
        .thenCancel()
        .verify();
    assertTrue(cancelled.get());
  }

  @Test
  void concurrentReleasePermitsImmediateRedeliveryAndSchemaChecksRemainNative() {
    var db =
        new FakeR2dbc()
            .onUpdate("INSERT INTO", ignored -> 0L)
            .onUpdate("SET owner", ignored -> 0L)
            .onQuery("SELECT", ignored -> java.util.List.of());
    var store = new R2dbcReactiveInboxStore(new R2dbcSql(db), "axiz_inbox");
    StepVerifier.create(
            store.tryAcquire("c", "m", "o", NOW, NOW.plusSeconds(10), NOW.plusSeconds(100)))
        .expectNext(Acquisition.busy(NOW))
        .verifyComplete();
    StepVerifier.create(store.verifySchema()).verifyComplete();
    var missing = new R2dbcReactiveInboxStore(new R2dbcSql(new FakeR2dbc()), "axiz_inbox");
    StepVerifier.create(missing.verifySchema())
        .expectErrorMatches(
            error ->
                error instanceof InboxException
                    && error.getMessage().contains("inbox-postgresql-upgrade-owner.sql"))
        .verify();
    assertThrows(
        IllegalArgumentException.class,
        () -> new R2dbcReactiveInboxStore(new R2dbcSql(db), "inbox;DROP"));
  }
}

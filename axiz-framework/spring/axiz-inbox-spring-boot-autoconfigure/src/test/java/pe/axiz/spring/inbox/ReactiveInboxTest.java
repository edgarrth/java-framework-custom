package pe.axiz.spring.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.adapter.inbox.r2dbc.ReactiveInboxStore;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.test.StepVerifier;

class ReactiveInboxTest {
  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

  @Test
  void r2dbcFacadeKeepsContextAndDoesNotSubmitBeforeSubscription() {
    var db =
        new FakeR2dbc()
            .onUpdate("INSERT INTO", ignored -> 1L)
            .onUpdate("SET status", ignored -> 1L)
            .onUpdate("DELETE", ignored -> 1L)
            .onQuery("SELECT", ignored -> List.of());
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizInboxR2dbcAutoConfiguration.class))
        .withPropertyValues("axiz.inbox.store=r2dbc")
        .withBean(ReactiveSql.class, () -> new R2dbcSql(db))
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveInbox.class)
                  .hasSingleBean(ReactiveInboxStore.class);
              var inbox = ctx.getBean(ReactiveInbox.class);
              var flow =
                  inbox
                      .tryAcquire("c", "m", "o", NOW, NOW.plusSeconds(10), NOW.plusSeconds(100))
                      .doOnNext(
                          acquired -> assertEquals(Acquisition.Status.ACQUIRED, acquired.status()))
                      .then(inbox.markProcessed("c", "m", "o", NOW))
                      .doOnNext(ReactiveInboxTest::assertOwned)
                      .then(inbox.release("c", "m", "o"))
                      .doOnNext(ReactiveInboxTest::assertOwned)
                      .then(inbox.purgeExpired(NOW))
                      .doOnNext(count -> assertEquals(1, count))
                      .then(inbox.verifySchema());
              assertTrue(db.executions().isEmpty());
              StepVerifier.create(flow).verifyComplete();
              assertEquals(5, db.executions().size());
            });
  }

  private static void assertOwned(boolean owned) {
    assertTrue(owned);
  }

  @Test
  void asyncFacadeDefersAndPreservesCancellationErrorsAndDefaultSchemaCheck() {
    var calls = new AtomicInteger();
    var pending = new CompletableFuture<Acquisition>();
    AsyncInboxStore store =
        new AsyncInboxStore() {
          public java.util.concurrent.CompletionStage<Acquisition> tryAcquire(
              String consumer,
              String messageId,
              String owner,
              Instant now,
              Instant leaseUntil,
              Instant expiresAt) {
            calls.incrementAndGet();
            return pending;
          }

          public java.util.concurrent.CompletionStage<Boolean> markProcessed(
              String consumer, String messageId, String owner, Instant at) {
            return CompletableFuture.completedFuture(true);
          }

          public java.util.concurrent.CompletionStage<Boolean> release(
              String consumer, String messageId, String owner) {
            return CompletableFuture.failedFuture(new IllegalStateException("offline"));
          }

          public java.util.concurrent.CompletionStage<Integer> purgeExpired(Instant now) {
            return CompletableFuture.completedFuture(0);
          }
        };
    var inbox = new AsyncInboxFacade(store);
    var acquisition =
        inbox.tryAcquire("c", "m", "o", NOW, NOW.plusSeconds(10), NOW.plusSeconds(100));
    assertEquals(0, calls.get());
    StepVerifier.create(acquisition).thenCancel().verify();
    assertEquals(1, calls.get());
    assertTrue(pending.isCancelled());
    StepVerifier.create(inbox.markProcessed("c", "m", "o", NOW)).expectNext(true).verifyComplete();
    StepVerifier.create(inbox.release("c", "m", "o")).expectErrorMessage("offline").verify();
    StepVerifier.create(inbox.purgeExpired(NOW)).expectNext(0).verifyComplete();
    StepVerifier.create(inbox.verifySchema()).verifyComplete();
  }

  @Test
  void disabledInboxDoesNotInitializeNativeOrPollClients() {
    for (String backend : List.of("dynamodb", "r2dbc")) {
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizInboxDynamoDbAutoConfiguration.class,
                  AxizInboxR2dbcAutoConfiguration.class,
                  AxizInboxAutoConfiguration.class))
          .withPropertyValues("axiz.inbox.store=" + backend, "axiz.inbox.enabled=false")
          .run(
              ctx ->
                  assertThat(ctx)
                      .hasNotFailed()
                      .doesNotHaveBean(ReactiveInbox.class)
                      .doesNotHaveBean(AsyncInboxStore.class)
                      .doesNotHaveBean(
                          software.amazon.awssdk.services.dynamodb.DynamoDbClient.class)
                      .doesNotHaveBean(
                          software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.class));
    }
  }
}

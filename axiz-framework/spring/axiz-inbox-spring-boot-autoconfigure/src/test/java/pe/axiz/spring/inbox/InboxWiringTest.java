package pe.axiz.spring.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.dynamodb.DynamoDbInboxStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** The purge of expired inbox rows and the DynamoDB store. */
class InboxWiringTest {

  /** Counts purges; it can fail on demand. */
  static class PurgeCounter implements InboxStore {
    final AtomicInteger purges = new AtomicInteger();
    final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public Acquisition tryAcquire(
        String consumer,
        String messageId,
        String owner,
        Instant now,
        Instant leaseUntil,
        Instant expiresAt) {
      return Acquisition.acquired();
    }

    @Override
    public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
      return true;
    }

    @Override
    public boolean release(String consumer, String messageId, String owner) {
      return true;
    }

    @Override
    public int purgeExpired(Instant now) {
      purges.incrementAndGet();
      if (failing.get()) {
        throw new IllegalStateException("database down");
      }
      return 2;
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), "condition not reached in time");
  }

  @Test
  void expiredRowsArePurgedOnScheduleAndAFailedPurgeIsRetried() throws Exception {
    PurgeCounter store = new PurgeCounter();
    InboxPurger purger = new InboxPurger(store, Duration.ofMillis(5), Clock.systemUTC());
    assertFalse(purger.isRunning());
    purger.start();
    try {
      assertTrue(purger.isRunning());
      await(() -> store.purges.get() >= 2);
      store.failing.set(true);
      int failedFrom = store.purges.get();
      await(() -> store.purges.get() >= failedFrom + 2);
    } finally {
      purger.stop();
    }
    assertFalse(purger.isRunning());
  }

  @Test
  void aPurgeThatSwallowsTheInterruptStillEndsTheWorker() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    PurgeCounter driverLike =
        new PurgeCounter() {
          @Override
          public int purgeExpired(Instant now) {
            calls.incrementAndGet();
            entered.countDown();
            try {
              Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
              // like some JDBC drivers: the interrupt is consumed and the call just returns
            }
            return 0;
          }
        };
    InboxPurger purger = new InboxPurger(driverLike, Duration.ofMillis(1), Clock.systemUTC());
    purger.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    purger.stop();
    Thread.sleep(50);
    assertEquals(1, calls.get(), "the stop flag ends the loop even without the interrupt");
  }

  @Test
  void theDynamoDbStoreUsesTheConfiguredTableAndEndpoint() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizInboxDynamoDbAutoConfiguration.class))
        .withPropertyValues(
            "axiz.inbox.store=dynamodb",
            "axiz.inbox.table=payments_inbox",
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=http://localhost:4566")
        .run(
            ctx -> {
              assertThat(ctx.getBean(InboxStore.class)).isInstanceOf(DynamoDbInboxStore.class);
              assertEquals(
                  URI.create("http://localhost:4566"),
                  ctx.getBean(DynamoDbClient.class)
                      .serviceClientConfiguration()
                      .endpointOverride()
                      .orElseThrow());
            });
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizInboxDynamoDbAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(InboxStore.class));
  }
}

package pe.axiz.adapter.lock.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.test.support.ImmediateAsyncFakes;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

class DynamoDbAsyncDistributedLockTest {
  static final class Time extends Clock {
    Instant now = Instant.parse("2026-10-30T10:00:00Z");

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

  @Test
  void ownershipExpiryRenewalAndCleanupMatchTheImperativeProtocol() {
    var backend = new DynamoDbDistributedLockTest.FakeDynamo();
    var clock = new Time();
    var nativeClient = ImmediateAsyncFakes.adapt(DynamoDbAsyncClient.class, backend);
    var locks = new DynamoDbAsyncDistributedLock(nativeClient, "stable-lock-table", clock);
    var first =
        locks
            .tryAcquire("account:1", Duration.ofSeconds(10))
            .toCompletableFuture()
            .join()
            .orElseThrow();
    assertEquals("account:1", first.resource());
    assertEquals("stable-lock-table", backend.lastPut.tableName());
    assertEquals(first.token(), backend.items.get("account:1").get("token").s());
    assertFalse(first.token().isBlank());
    assertTrue(
        locks
            .tryAcquire("account:1", Duration.ofSeconds(1))
            .toCompletableFuture()
            .join()
            .isEmpty());
    clock.now = clock.now.plusSeconds(5);
    assertTrue(first.renew(Duration.ofSeconds(20)).toCompletableFuture().join());
    assertEquals(
        clock.millis() + 20_000,
        Long.parseLong(backend.items.get("account:1").get("leaseUntil").n()));
    clock.now = clock.now.plusSeconds(21);
    var next =
        locks
            .tryAcquire("account:1", Duration.ofSeconds(10))
            .toCompletableFuture()
            .join()
            .orElseThrow();
    assertNotEquals(first.token(), next.token());
    assertFalse(first.renew(Duration.ofSeconds(10)).toCompletableFuture().join());
    first.release().toCompletableFuture().join();
    assertTrue(
        backend.items.containsKey("account:1"),
        "stale release cannot remove the new owner's lease");
    assertThrows(IllegalArgumentException.class, () -> next.renew(Duration.ZERO));
    next.release().toCompletableFuture().join();
    assertFalse(backend.items.containsKey("account:1"));
    assertFalse(next.renew(Duration.ofSeconds(10)).toCompletableFuture().join());
    assertThrows(IllegalArgumentException.class, () -> locks.tryAcquire("x", Duration.ZERO));
  }

  @Test
  void infrastructureFailuresAreNotReportedAsLeaseContention() {
    var backend = new DynamoDbDistributedLockTest.FakeDynamo();
    var locks =
        new DynamoDbAsyncDistributedLock(
            ImmediateAsyncFakes.adapt(DynamoDbAsyncClient.class, backend), "locks", new Time());
    var held =
        locks
            .tryAcquire("account:1", Duration.ofSeconds(10))
            .toCompletableFuture()
            .join()
            .orElseThrow();
    backend.offline = true;
    assertEquals(
        "backend offline",
        assertThrows(
                CompletionException.class,
                () ->
                    locks
                        .tryAcquire("account:2", Duration.ofSeconds(10))
                        .toCompletableFuture()
                        .join())
            .getCause()
            .getMessage());
    assertThrows(
        CompletionException.class,
        () -> held.renew(Duration.ofSeconds(10)).toCompletableFuture().join());
    assertThrows(CompletionException.class, () -> held.release().toCompletableFuture().join());
  }

  @Test
  void cancellationReachesTheNativeAwsFuture() {
    var pending = new CompletableFuture<PutItemResponse>();
    var nativeClient =
        (DynamoDbAsyncClient)
            Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbAsyncClient.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("putItem")) return pending;
                  throw new UnsupportedOperationException(method.getName());
                });
    var locks = new DynamoDbAsyncDistributedLock(nativeClient, "locks", new Time());
    locks.tryAcquire("account:1", Duration.ofSeconds(10)).toCompletableFuture().cancel(true);
    assertTrue(pending.isCancelled());
  }
}

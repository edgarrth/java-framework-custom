package pe.axiz.core.lock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LeaseExpiryTest {
  @Test
  void anExpiredOwnerCannotResurrectItsLeaseBeforeAnotherOwnerAcquiresIt() {
    var clock = new LockTest.MutableClock();
    var locks = new InMemoryDistributedLock(clock);
    Duration lease = Duration.ofSeconds(10);
    var expired = locks.tryAcquire("account", lease).orElseThrow();
    clock.now = clock.now.plus(lease);
    assertFalse(expired.renew(lease));
    var current = locks.tryAcquire("account", lease).orElseThrow();
    assertNotEquals(expired.token(), current.token());
    expired.release();
    assertTrue(locks.tryAcquire("account", lease).isEmpty());
    assertTrue(current.renew(lease));
    current.release();
    assertFalse(current.renew(lease));
  }

  @Test
  void theNativeStoreHonorsTheSameExactExpiryBoundary() {
    var clock = new LockTest.MutableClock();
    var locks = new InMemoryAsyncDistributedLock(clock);
    Duration lease = Duration.ofSeconds(10);
    var expired = locks.tryAcquire("account", lease).toCompletableFuture().join().orElseThrow();
    clock.now = clock.now.plus(lease);
    assertFalse(expired.renew(lease).toCompletableFuture().join());
    var current = locks.tryAcquire("account", lease).toCompletableFuture().join().orElseThrow();
    assertNotEquals(expired.token(), current.token());
    expired.release().toCompletableFuture().join();
    assertTrue(locks.tryAcquire("account", lease).toCompletableFuture().join().isEmpty());
    current.release().toCompletableFuture().join();
    assertTrue(locks.tryAcquire("account", lease).toCompletableFuture().join().isPresent());
  }
}

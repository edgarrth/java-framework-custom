package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class EnvelopeCoalescingTest {
  @Test
  void remoteLoadForOneContextDoesNotBlockAnotherAndEveryCallerConsumesOneUse() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger generated = new AtomicInteger();
    byte[] raw = AesGcm.newKey().getEncoded();
    DataKeyProvider provider =
        new DataKeyProvider() {
          public DataKey generate(String context) {
            generated.incrementAndGet();
            if (context.equals("slow")) {
              inside.countDown();
              try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            }
            return new DataKey(raw, new byte[] {1});
          }

          public byte[] decrypt(byte[] key, String context) {
            return raw.clone();
          }
        };
    EnvelopeEncryptor envelope =
        new EnvelopeEncryptor(provider, Duration.ofMinutes(1), 1, Clock.systemUTC());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<byte[]> slow = executor.submit(() -> envelope.encrypt(new byte[] {1}, "slow"));
      assertTrue(inside.await(5, TimeUnit.SECONDS));
      try {
        Future<byte[]> fast = executor.submit(() -> envelope.encrypt(new byte[] {2}, "fast"));
        assertArrayEquals(new byte[] {2}, envelope.decrypt(fast.get(2, TimeUnit.SECONDS), "fast"));
      } finally {
        release.countDown();
      }
      slow.get(2, TimeUnit.SECONDS);
      int before = generated.get();
      List<Future<byte[]>> futures = new ArrayList<>();
      for (int i = 0; i < 50; i++)
        futures.add(executor.submit(() -> envelope.encrypt(new byte[0], "shared")));
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
      assertEquals(
          50, generated.get() - before, "maxUses=1 cannot share a use between waiting callers");
    }
  }

  @Test
  void nativeAndSynchronousEnvelopesInteroperateWithBindingAndRejectVersionChanges() {
    byte[] raw = AesGcm.newKey().getEncoded();
    DataKeyProvider sync =
        new DataKeyProvider() {
          public DataKey generate(String context) {
            return new DataKey(raw, new byte[] {1});
          }

          public byte[] decrypt(byte[] wrapped, String context) {
            return raw.clone();
          }
        };
    AsyncDataKeyProvider async =
        new AsyncDataKeyProvider() {
          public java.util.concurrent.CompletionStage<DataKeyProvider.DataKey> generate(
              String context) {
            return CompletableFuture.completedFuture(sync.generate(context));
          }

          public java.util.concurrent.CompletionStage<byte[]> decrypt(byte[] key, String context) {
            return CompletableFuture.completedFuture(sync.decrypt(key, context));
          }
        };
    var encryptor = new EnvelopeEncryptor(sync, Duration.ofMinutes(1), 100, Clock.systemUTC());
    var nativeEncryptor =
        new AsyncEnvelopeEncryptor(async, Duration.ofMinutes(1), 100, Clock.systemUTC());
    byte[] value = {0, 1, 2};
    byte[] sealed = nativeEncryptor.encrypt(value, "records", "row1").toCompletableFuture().join();
    assertArrayEquals(value, encryptor.decrypt(sealed, "records", "row1"));
    assertThrows(CryptoException.class, () -> encryptor.decrypt(sealed, "records", "row2"));
    assertThrows(CryptoException.class, () -> encryptor.decrypt(sealed, "records"));
    assertArrayEquals(
        value,
        nativeEncryptor
            .decrypt(encryptor.encrypt(value, "records", "row1"), "records", "row1")
            .toCompletableFuture()
            .join());
    sealed[0] = 2;
    assertThrows(CryptoException.class, () -> encryptor.decrypt(sealed, "records", "row1"));
  }
}

package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

class AsyncEnvelopeEncryptorTest {
  private static final byte[] PLAIN = "statement".getBytes(StandardCharsets.UTF_8);

  /** Emulador de claves solo de CPU; aquí no se adapta ninguna operación remota síncrona. */
  private static AsyncDataKeyProvider immediate(EnvelopeKeyCacheTest.FakeKms fake) {
    return new AsyncDataKeyProvider() {
      public CompletionStage<DataKey> generate(String context) {
        return Stages.call(() -> CompletableFuture.completedFuture(fake.generate(context)));
      }

      public CompletionStage<byte[]> decrypt(byte[] key, String context) {
        return Stages.call(() -> CompletableFuture.completedFuture(fake.decrypt(key, context)));
      }
    };
  }

  private static AsyncEnvelopeEncryptor encryptor(
      AsyncDataKeyProvider keys, long uses, Clock clock) {
    return new AsyncEnvelopeEncryptor(keys, Duration.ofMinutes(1), uses, clock);
  }

  @Test
  void nativeAndSynchronousEnvelopesRemainInteroperableAndAuthenticateContext() {
    var keys = new EnvelopeKeyCacheTest.FakeKms();
    var nativeEnvelope = encryptor(immediate(keys), 100, Clock.systemUTC());
    var sync = new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC());
    byte[] nativeSealed = nativeEnvelope.encrypt(PLAIN, "account").toCompletableFuture().join();
    assertArrayEquals(PLAIN, sync.decrypt(nativeSealed, "account"));
    assertArrayEquals(
        PLAIN,
        nativeEnvelope
            .decrypt(sync.encrypt(PLAIN, "account"), "account")
            .toCompletableFuture()
            .join());
    var wrong = nativeEnvelope.decrypt(nativeSealed, "another-account").toCompletableFuture();
    assertInstanceOf(
        CryptoException.class, assertThrows(CompletionException.class, wrong::join).getCause());
  }

  @Test
  void pendingCallersShareOneLoadAndLastCancellationReachesTheProvider() {
    var keys = new PendingKeys();
    var envelope = encryptor(keys, 100, Clock.systemUTC());
    var first = envelope.encrypt(PLAIN, "account").toCompletableFuture();
    var second = envelope.encrypt(PLAIN, "account").toCompletableFuture();
    assertEquals(1, keys.calls.get());
    var nativeRequest = keys.loads.element().future();
    assertTrue(first.cancel(true));
    assertFalse(nativeRequest.isDone());
    assertFalse(second.isDone());
    assertTrue(second.cancel(true));
    assertTrue(nativeRequest.isCancelled());
    var retry = envelope.encrypt(PLAIN, "account").toCompletableFuture();
    assertEquals(2, keys.calls.get());
    keys.completeAll();
    assertTrue(retry.join().length > PLAIN.length);
  }

  @Test
  void concurrentReservationsNeverExceedThePerKeyUseBound() throws Exception {
    var keys = new PendingKeys();
    var envelope = encryptor(keys, 2, Clock.systemUTC());
    List<CompletableFuture<byte[]>> results;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var submissions =
          IntStream.range(0, 100)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> envelope.encrypt(PLAIN, "account").toCompletableFuture()))
              .toList();
      var collected = new ArrayList<CompletableFuture<byte[]>>();
      for (var submission : submissions) collected.add(submission.get());
      results = List.copyOf(collected);
    }
    assertEquals(50, keys.calls.get(), "each load serves exactly two reserved uses");
    keys.completeAll();
    for (var result : results) assertTrue(result.join().length > PLAIN.length);
  }

  @Test
  void inputBytesAreOwnedAcrossAsynchronousCompletion() {
    var keys = new PendingKeys();
    var envelope = encryptor(keys, 100, Clock.systemUTC());
    byte[] input = PLAIN.clone();
    var encryption = envelope.encrypt(input, "account").toCompletableFuture();
    Arrays.fill(input, (byte) 0);
    keys.completeAll();
    byte[] sealed = encryption.join();
    var decryption = envelope.decrypt(sealed, "account").toCompletableFuture();
    Arrays.fill(sealed, (byte) 0);
    keys.decrypt.complete(keys.plaintext.clone());
    assertArrayEquals(PLAIN, decryption.join());
  }

  @Test
  void keysExpireAtTheAgeBoundaryAndDecryptIdentityIncludesEveryWrappedByteAndContext() {
    var keys = new EnvelopeKeyCacheTest.FakeKms();
    var clock = new EnvelopeKeyCacheTest.MovingClock();
    var envelope = encryptor(immediate(keys), 100, clock);
    byte[] sealed = envelope.encrypt(PLAIN, "account").toCompletableFuture().join();
    envelope.encrypt(PLAIN, "account").toCompletableFuture().join();
    assertEquals(1, keys.generated.get());
    envelope.decrypt(sealed, "account").toCompletableFuture().join();
    envelope.decrypt(sealed, "account").toCompletableFuture().join();
    assertEquals(1, keys.decrypted.get());
    clock.now = clock.now.plusSeconds(60);
    envelope.encrypt(PLAIN, "account").toCompletableFuture().join();
    envelope.decrypt(sealed, "account").toCompletableFuture().join();
    assertEquals(2, keys.generated.get());
    assertEquals(2, keys.decrypted.get());
    byte[] wrappedA = {0, 31};
    byte[] wrappedB = {1, 0}; // mismo Arrays.hashCode, distintos bytes de clave
    byte[] keyA = AesGcm.newKey().getEncoded();
    byte[] keyB = AesGcm.newKey().getEncoded();
    keys.register(wrappedA, keyA);
    keys.register(wrappedB, keyB);
    assertArrayEquals(
        PLAIN,
        envelope
            .decrypt(EnvelopePayload.seal(PLAIN, keyA.clone(), wrappedA, "account"), "account")
            .toCompletableFuture()
            .join());
    assertArrayEquals(
        PLAIN,
        envelope
            .decrypt(EnvelopePayload.seal(PLAIN, keyB.clone(), wrappedB, "account"), "account")
            .toCompletableFuture()
            .join());
  }

  @Test
  void bothCachesEvictOldEntriesAndPendingAdmissionIsBounded() {
    var keys = new EnvelopeKeyCacheTest.FakeKms();
    var envelope = encryptor(immediate(keys), 100, Clock.systemUTC());
    byte[] first = envelope.encrypt(PLAIN, "first").toCompletableFuture().join();
    envelope.decrypt(first, "first").toCompletableFuture().join();
    for (int i = 0; i < EnvelopeEncryptor.MAX_CACHED_KEYS; i++) {
      String context = "context-" + i;
      var sealed = envelope.encrypt(PLAIN, context).toCompletableFuture().join();
      envelope.decrypt(sealed, context).toCompletableFuture().join();
    }
    int generated = keys.generated.get();
    int decrypted = keys.decrypted.get();
    envelope.encrypt(PLAIN, "first").toCompletableFuture().join();
    envelope.decrypt(first, "first").toCompletableFuture().join();
    assertEquals(generated + 1, keys.generated.get());
    assertEquals(decrypted + 1, keys.decrypted.get());

    var pending = new PendingKeys();
    var bounded = encryptor(pending, 100, Clock.systemUTC());
    var active = new ArrayList<CompletableFuture<byte[]>>();
    for (int i = 0; i < EnvelopeEncryptor.MAX_CACHED_KEYS; i++)
      active.add(bounded.encrypt(PLAIN, "pending-" + i).toCompletableFuture());
    var rejected = bounded.encrypt(PLAIN, "overflow").toCompletableFuture();
    assertInstanceOf(
        RejectedExecutionException.class,
        assertThrows(CompletionException.class, rejected::join).getCause());
    active.getFirst().cancel(true);
    active.add(bounded.encrypt(PLAIN, "overflow").toCompletableFuture());
    assertEquals(EnvelopeEncryptor.MAX_CACHED_KEYS + 1, pending.calls.get());
    active.forEach(future -> future.cancel(true));
    assertTrue(pending.loads.stream().allMatch(load -> load.future().isCancelled()));
  }

  @Test
  void malformedEnvelopesAndKeysFailWithoutPoisoningTheCache() {
    var keys = new PendingKeys();
    var envelope = encryptor(keys, 100, Clock.systemUTC());
    for (byte[] malformed : List.of(new byte[2], new byte[] {0, 0, 1}, new byte[] {0, 1, 1})) {
      var result = envelope.decrypt(malformed, "account").toCompletableFuture();
      assertInstanceOf(
          CryptoException.class, assertThrows(CompletionException.class, result::join).getCause());
    }
    assertFalse(keys.decrypt.isDone());
    var invalid = envelope.encrypt(PLAIN, "account").toCompletableFuture();
    keys.loads.element().future().complete(new DataKey(new byte[1], new byte[] {1}));
    assertInstanceOf(
        CryptoException.class, assertThrows(CompletionException.class, invalid::join).getCause());
    var retry = envelope.encrypt(PLAIN, "account").toCompletableFuture();
    assertEquals(2, keys.calls.get());
    keys.completeAll();
    assertTrue(retry.join().length > PLAIN.length);
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncEnvelopeEncryptor(keys, Duration.ZERO, 1, Clock.systemUTC()));
    assertThrows(IllegalArgumentException.class, () -> encryptor(keys, 0, Clock.systemUTC()));
  }

  private record Load(int id, CompletableFuture<DataKey> future) {}

  private static final class PendingKeys implements AsyncDataKeyProvider {
    final byte[] plaintext = AesGcm.newKey().getEncoded();
    final AtomicInteger calls = new AtomicInteger();
    final ConcurrentLinkedQueue<Load> loads = new ConcurrentLinkedQueue<>();
    final CompletableFuture<byte[]> decrypt = new CompletableFuture<>();

    public CompletionStage<DataKey> generate(String context) {
      var future = new CompletableFuture<DataKey>();
      loads.add(new Load(calls.incrementAndGet(), future));
      return future;
    }

    public CompletionStage<byte[]> decrypt(byte[] encryptedKey, String context) {
      return decrypt;
    }

    void completeAll() {
      for (var load : loads)
        load.future()
            .complete(new DataKey(plaintext, ByteBuffer.allocate(4).putInt(load.id()).array()));
    }
  }
}

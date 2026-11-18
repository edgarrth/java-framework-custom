package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/** Examines owned buffers because a closed API alone does not prove erasure of key material. */
class CryptoErasureContractTest {
  private static byte[] material(int size) {
    byte[] bytes = new byte[size];
    Arrays.fill(bytes, (byte) 83);
    return bytes;
  }

  private static Object owned(Object object, String field) throws ReflectiveOperationException {
    var member = object.getClass().getDeclaredField(field);
    member.setAccessible(true);
    return member.get(object);
  }

  private static void erased(byte[] bytes) {
    assertArrayEquals(new byte[bytes.length], bytes);
  }

  @Test
  void revokedKeysAreActuallyErasedWithoutChangingCallerBuffers() throws Exception {
    byte[] input = material(32);
    var fieldKeys = StaticKeyProvider.fromBytes("key", Map.of("key", input));
    var indexKeys = new BlindIndex(Map.of("key", input), "key");
    var dataKey = new DataKey(input, new byte[] {1});
    var fieldMemory = (Map<?, ?>) owned(fieldKeys, "keys");
    var indexMemory = (Map<?, ?>) owned(indexKeys, "keys");
    byte[] dekMemory = (byte[]) owned(dataKey, "plaintext");
    assertArrayEquals(input, dekMemory);
    fieldKeys.close();
    indexKeys.close();
    dataKey.close();
    for (Object bytes : fieldMemory.values()) erased((byte[]) bytes);
    for (Object bytes : indexMemory.values()) erased((byte[]) bytes);
    erased(dekMemory);
    assertArrayEquals(material(32), input);
    assertThrows(
        CryptoException.class,
        () -> indexKeys.index("x", new IndexScope("p", "t", "a", "d", "i", 1)));
  }

  @Test
  void partialConstructionDisposesEveryAcquiredKeyIncludingTheInvalidOne() {
    byte[] good = material(32);
    byte[] bad = material(31);
    Map<String, SecretKey> keys = new LinkedHashMap<>();
    keys.put("first", new TransferredKey(good));
    keys.put("second", new TransferredKey(bad));
    assertThrows(CryptoException.class, () -> new StaticKeyProvider("first", keys));
    erased(good);
    erased(bad);
  }

  private static final class TransferredKey implements SecretKey {
    private static final long serialVersionUID = 1L;
    private final byte[] value;

    TransferredKey(byte[] value) {
      this.value = value;
    }

    public String getAlgorithm() {
      return "AES";
    }

    public String getFormat() {
      return "RAW";
    }

    public byte[] getEncoded() {
      return value;
    }
  }

  @Test
  void envelopeConsumesRawKeysEvenWhenAuthenticationOrValidationFails() {
    byte[] key = material(32);
    byte[] encoded = EnvelopePayload.seal(new byte[] {8, 9}, key, new byte[] {1}, "ctx", "row");
    erased(key);
    key = material(32);
    assertArrayEquals(new byte[] {8, 9}, EnvelopePayload.parse(encoded).open(key, "ctx", "row"));
    erased(key);
    byte[] wrongContextKey = material(32);
    assertThrows(
        CryptoException.class,
        () -> EnvelopePayload.parse(encoded).open(wrongContextKey, "ctx", "other"));
    erased(wrongContextKey);
    byte[] invalidKey = material(31);
    assertThrows(
        CryptoException.class,
        () -> EnvelopePayload.seal(new byte[] {1}, invalidKey, new byte[] {1}, "ctx"));
    erased(invalidKey);
  }

  @Test
  void stringAdaptersDisposeTransferredPlaintextOnSuccessFailureAndCancellation() {
    var input = new AtomicReference<byte[]>();
    byte[] plaintext = material(3);
    var protector =
        new PayloadProtector() {
          public byte[] protect(byte[] value, String context, String binding) {
            input.set(value);
            return value.clone();
          }

          public byte[] reveal(byte[] value, String context, String binding) {
            return plaintext;
          }
        };
    assertEquals("axzp1:c2VjcmV0", protector.protect("secret", "ctx", "row"));
    erased(input.get());
    assertEquals("SSS", protector.reveal("axzp1:AA", "ctx", "row"));
    erased(plaintext);
    var pending =
        new CompletableFuture<byte[]>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    var nativeProtector =
        new AsyncPayloadProtector() {
          public CompletionStage<byte[]> protect(byte[] value, String context, String binding) {
            input.set(value);
            return pending;
          }

          public CompletionStage<byte[]> reveal(byte[] value, String context, String binding) {
            input.set(value);
            return pending;
          }
        };
    var result = nativeProtector.protect("secret", "ctx", "row").toCompletableFuture();
    erased(input.get());
    assertTrue(result.cancel(true));
    byte[] late = material(4);
    assertTrue(pending.complete(late));
    erased(late);
    byte[] returned = "revealed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var success =
        new AsyncPayloadProtector() {
          public CompletionStage<byte[]> protect(byte[] value, String context, String binding) {
            input.set(value);
            return CompletableFuture.completedFuture(returned);
          }

          public CompletionStage<byte[]> reveal(byte[] value, String context, String binding) {
            input.set(value);
            return CompletableFuture.completedFuture(returned);
          }
        };
    assertEquals("revealed", success.reveal("axzp1:AQ", "ctx", "row").toCompletableFuture().join());
    erased(input.get());
    erased(returned);
    assertEquals(
        "axzp1:AAAAAAAAAAA", success.protect("secret", "ctx", "row").toCompletableFuture().join());
    erased(input.get());
  }

  @Test
  void closingEitherStackRevokesBothEncryptAndDecryptCaches() {
    var keys = new EnvelopeKeyCacheTest.FakeKms();
    var sync = new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC());
    byte[] encoded = sync.encrypt(new byte[] {1}, "ctx");
    assertArrayEquals(new byte[] {1}, sync.decrypt(encoded, "ctx"));
    sync.close();
    assertThrows(RuntimeException.class, () -> sync.decrypt(encoded, "ctx"));
    var async =
        new AsyncEnvelopeEncryptor(
            KeyringDataKeyProviderTest.nativeKeys(keys),
            Duration.ofMinutes(1),
            100,
            Clock.systemUTC());
    byte[] nativeEncoded = async.encrypt(new byte[] {2}, "ctx").toCompletableFuture().join();
    assertArrayEquals(
        new byte[] {2}, async.decrypt(nativeEncoded, "ctx").toCompletableFuture().join());
    async.close();
    assertThrows(
        CompletionException.class,
        () -> async.encrypt(new byte[] {3}, "ctx").toCompletableFuture().join());
    assertThrows(
        CompletionException.class,
        () -> async.decrypt(nativeEncoded, "ctx").toCompletableFuture().join());
  }
}

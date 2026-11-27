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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

class EnvelopeKeyOwnershipTest {
  @Test
  void expiryAndCloseEraseOwnedKeysWhileCiphertextsRemainDecryptableWithTheProvider() {
    List<DataKey> generated = new ArrayList<>();
    List<byte[]> decrypted = new ArrayList<>();
    byte[] master = AesGcm.newKey().getEncoded();
    DataKeyProvider provider =
        new DataKeyProvider() {
          public DataKey generate(String context) {
            var key = new DataKey(master, new byte[] {1});
            generated.add(key);
            return key;
          }

          public byte[] decrypt(byte[] wrapped, String context) {
            byte[] owned = master.clone();
            decrypted.add(owned);
            return owned;
          }
        };
    var envelope = new EnvelopeEncryptor(provider, Duration.ofMinutes(1), 1, Clock.systemUTC());
    byte[] first = envelope.encrypt(new byte[] {1}, "account");
    byte[] second = envelope.encrypt(new byte[] {2}, "account");
    assertEquals(2, generated.size());
    assertThrows(CryptoException.class, generated.getFirst()::plaintext);
    assertArrayEquals(new byte[] {1}, envelope.decrypt(first, "account"));
    assertArrayEquals(new byte[] {2}, envelope.decrypt(second, "account"));
    assertEquals(1, decrypted.size());
    assertArrayEquals(new byte[32], decrypted.getFirst());
    envelope.close();
    for (var key : generated) assertThrows(CryptoException.class, key::plaintext);
    assertThrows(RuntimeException.class, () -> envelope.encrypt(new byte[] {3}, "account"));
  }

  @Test
  void cancellingNativeEncryptionErasesAnUncancellableLateKey() {
    var pending =
        new CompletableFuture<DataKey>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    AsyncDataKeyProvider provider =
        new AsyncDataKeyProvider() {
          public CompletionStage<DataKey> generate(String context) {
            return pending;
          }

          public CompletionStage<byte[]> decrypt(byte[] key, String context) {
            throw new AssertionError();
          }
        };
    try (var envelope =
        new AsyncEnvelopeEncryptor(provider, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      var result = envelope.encrypt(new byte[] {1}, "account").toCompletableFuture();
      assertTrue(result.cancel(true));
      var late = new DataKey(new byte[32], new byte[] {1});
      pending.complete(late);
      assertThrows(CryptoException.class, late::plaintext);
    }
  }

  @Test
  void invalidNativeKeysAreDisposedInsteadOfBeingCachedOrRetriedImplicitly() {
    var invalid = new DataKey(new byte[1], new byte[] {1});
    AsyncDataKeyProvider provider =
        new AsyncDataKeyProvider() {
          public CompletionStage<DataKey> generate(String context) {
            return CompletableFuture.completedFuture(invalid);
          }

          public CompletionStage<byte[]> decrypt(byte[] key, String context) {
            throw new AssertionError();
          }
        };
    try (var envelope =
        new AsyncEnvelopeEncryptor(provider, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      assertThrows(
          CompletionException.class,
          () -> envelope.encrypt(new byte[] {1}, "account").toCompletableFuture().join());
      assertThrows(CryptoException.class, invalid::plaintext);
    }
  }
}

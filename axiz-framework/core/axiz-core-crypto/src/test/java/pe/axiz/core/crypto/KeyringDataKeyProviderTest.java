package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

class KeyringDataKeyProviderTest {
  static final String CONTEXT = "purpose:token-vault";
  static final byte[] PLAIN = "synthetic document".getBytes(StandardCharsets.UTF_8);

  static final class WrappingKeys implements DataKeyProvider {
    final SecretKey master = AesGcm.newKey();
    final AtomicInteger decrypts = new AtomicInteger();

    @Override
    public DataKey generate(String context) {
      byte[] raw = AesGcm.newKey().getEncoded();
      try {
        return new DataKey(
            raw, AesGcm.encrypt(master, raw, context.getBytes(StandardCharsets.UTF_8)));
      } finally {
        Arrays.fill(raw, (byte) 0);
      }
    }

    @Override
    public byte[] decrypt(byte[] encrypted, String context) {
      decrypts.incrementAndGet();
      return AesGcm.decrypt(master, encrypted, context.getBytes(StandardCharsets.UTF_8));
    }
  }

  static AsyncDataKeyProvider nativeKeys(DataKeyProvider keys) {
    return new AsyncDataKeyProvider() {
      @Override
      public CompletionStage<DataKey> generate(String context) {
        return CompletableFuture.completedFuture(keys.generate(context));
      }

      @Override
      public CompletionStage<byte[]> decrypt(byte[] encrypted, String context) {
        return CompletableFuture.completedFuture(keys.decrypt(encrypted, context));
      }
    };
  }

  static EnvelopeEncryptor envelope(DataKeyProvider keys) {
    return new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC());
  }

  @Test
  void dualReadsShareFormatAcrossNativeAndSyncWithoutTryingOtherKeys() {
    var old = new WrappingKeys();
    var next = new WrappingKeys();
    try (var source = envelope(new KeyringDataKeyProvider(Map.of("old", old), "old"));
        var target = envelope(new KeyringDataKeyProvider(Map.of("old", old, "new", next), "new"));
        var nativeTarget =
            new AsyncEnvelopeEncryptor(
                new KeyringAsyncDataKeyProvider(
                    Map.of("old", nativeKeys(old), "new", nativeKeys(next)), "new"),
                Duration.ofMinutes(1),
                100,
                Clock.systemUTC())) {
      byte[] original = source.encrypt(PLAIN, CONTEXT, "token-binding");
      assertEquals("old", KeyringDataKeyProvider.envelopeKeyId(original));
      assertArrayEquals(PLAIN, target.decrypt(original, CONTEXT, "token-binding"));
      assertEquals(0, next.decrypts.get());
      byte[] migrated =
          nativeTarget.encrypt(PLAIN, CONTEXT, "token-binding").toCompletableFuture().join();
      assertEquals("new", KeyringDataKeyProvider.envelopeKeyId(migrated));
      assertArrayEquals(PLAIN, target.decrypt(migrated, CONTEXT, "token-binding"));
      assertArrayEquals(
          PLAIN,
          nativeTarget.decrypt(original, CONTEXT, "token-binding").toCompletableFuture().join());
      assertThrows(
          CryptoException.class, () -> target.decrypt(migrated, CONTEXT, "another-binding"));
      assertThrows(CryptoException.class, () -> source.decrypt(migrated, CONTEXT, "token-binding"));
      try (var retired = envelope(new KeyringDataKeyProvider(Map.of("new", next), "new"))) {
        assertThrows(
            CryptoException.class, () -> retired.decrypt(original, CONTEXT, "token-binding"));
        assertArrayEquals(PLAIN, retired.decrypt(migrated, CONTEXT, "token-binding"));
      }
    }
  }

  @Test
  void routeTamperingFailsAuthenticationEvenWhenTwoNamesShareTheSameUnderlyingKey() {
    var underlying = new WrappingKeys();
    var ring = new KeyringDataKeyProvider(Map.of("old", underlying, "new", underlying), "old");
    try (var key = ring.generate(CONTEXT)) {
      byte[] tampered = key.encrypted();
      System.arraycopy("new".getBytes(StandardCharsets.US_ASCII), 0, tampered, 5, 3);
      assertThrows(CryptoException.class, () -> ring.decrypt(tampered, CONTEXT));
      assertThrows(CryptoException.class, () -> ring.decrypt(key.encrypted(), "another-context"));
      byte[] appended = Arrays.copyOf(key.encrypted(), key.encrypted().length + 1);
      int calls = underlying.decrypts.get();
      assertThrows(CryptoException.class, () -> ring.decrypt(appended, CONTEXT));
      for (int size = 0; size < 10; size++) {
        byte[] truncated = Arrays.copyOf(key.encrypted(), size);
        assertThrows(CryptoException.class, () -> ring.decrypt(truncated, CONTEXT));
      }
      assertEquals(calls, underlying.decrypts.get());
      assertThrows(
          CryptoException.class,
          () -> new KeyringDataKeyProvider(Map.of("absent", underlying), "old"));
    }
  }

  @Test
  void configurationIsImmutableAndOwnedKeysAreClosedOnLateCancelledGeneration() {
    var provider = new WrappingKeys();
    var input = new HashMap<String, DataKeyProvider>();
    input.put("old", provider);
    var ring = new KeyringDataKeyProvider(input, "old");
    input.clear();
    assertEquals(1, ring.readKeyIds().size());
    assertThrows(UnsupportedOperationException.class, () -> ring.readKeyIds().clear());
    var pending =
        new CompletableFuture<DataKey>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    var async =
        new AsyncDataKeyProvider() {
          @Override
          public CompletionStage<DataKey> generate(String context) {
            return pending;
          }

          @Override
          public CompletionStage<byte[]> decrypt(byte[] encrypted, String context) {
            return CompletableFuture.failedFuture(new AssertionError("Not used"));
          }
        };
    var result =
        new KeyringAsyncDataKeyProvider(Map.of("old", async), "old")
            .generate(CONTEXT)
            .toCompletableFuture();
    assertTrue(result.cancel(true));
    var owned = provider.generate(CONTEXT);
    pending.complete(owned);
    assertThrows(CryptoException.class, owned::plaintext);
  }

  @Test
  void lateCancelledNativeDecryptWipesTheOwnedRawKey() {
    var keys = new WrappingKeys();
    try (var encoded = new KeyringDataKeyProvider(Map.of("old", keys), "old").generate(CONTEXT)) {
      var pending =
          new CompletableFuture<byte[]>() {
            @Override
            public boolean cancel(boolean interrupt) {
              return false;
            }
          };
      var async =
          new AsyncDataKeyProvider() {
            @Override
            public CompletionStage<DataKey> generate(String context) {
              return CompletableFuture.failedFuture(new AssertionError("Not used"));
            }

            @Override
            public CompletionStage<byte[]> decrypt(byte[] encrypted, String context) {
              return pending;
            }
          };
      var result =
          new KeyringAsyncDataKeyProvider(Map.of("old", async), "old")
              .decrypt(encoded.encrypted(), CONTEXT)
              .toCompletableFuture();
      assertTrue(result.cancel(true));
      byte[] raw = new byte[32];
      Arrays.fill(raw, (byte) 9);
      pending.complete(raw);
      assertArrayEquals(new byte[32], raw);
    }
  }
}

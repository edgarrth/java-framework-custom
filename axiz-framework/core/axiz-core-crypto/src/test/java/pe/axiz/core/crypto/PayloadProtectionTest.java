package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

class PayloadProtectionTest {
  @Test
  void ciphertextCannotMoveBetweenRowsOrPurposesAndDisablingProtectionFailsClosed() {
    var keys = new EnvelopeKeyCacheTest.FakeKms();
    try (var envelope =
        new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      var protection = PayloadProtectionMode.ENVELOPE.create(() -> envelope);
      String original = "{\"document\":\"00123456\"}";
      String stored = protection.protect(original, "purpose:outbox:payments", "row-1");
      assertTrue(stored.startsWith("axzp1:"));
      assertFalse(stored.contains("00123456"));
      assertEquals(original, protection.reveal(stored, "purpose:outbox:payments", "row-1"));
      assertThrows(
          CryptoException.class,
          () -> protection.reveal(stored, "purpose:outbox:payments", "row-2"));
      assertThrows(
          CryptoException.class, () -> protection.reveal(stored, "purpose:saga:payments", "row-1"));
      assertThrows(
          CryptoException.class,
          () -> PayloadProtector.NONE.reveal(stored, "purpose:outbox:payments", "row-1"));
      assertThrows(
          CryptoException.class,
          () -> protection.reveal(original, "purpose:outbox:payments", "row-1"));
      byte[] bytes =
          protection.protect(original.getBytes(StandardCharsets.UTF_8), "context", "tenant:row");
      assertThrows(
          CryptoException.class,
          () -> PayloadProtector.NONE.reveal(bytes, "context", "tenant:row"));
      bytes[bytes.length - 1] ^= 1;
      assertThrows(CryptoException.class, () -> protection.reveal(bytes, "context", "tenant:row"));
    }
  }

  @Test
  void nativeAndImperativeFormatsInteroperateAndInputsRemainOwnedByTheCaller() {
    var keys = new EnvelopeKeyCacheTest.FakeKms();
    var nativeKeys =
        new AsyncDataKeyProvider() {
          public CompletionStage<DataKey> generate(String context) {
            return CompletableFuture.completedFuture(keys.generate(context));
          }

          public CompletionStage<byte[]> decrypt(byte[] key, String context) {
            return CompletableFuture.completedFuture(keys.decrypt(key, context));
          }
        };
    try (var sync = new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC());
        var async =
            new AsyncEnvelopeEncryptor(nativeKeys, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      var imperative = PayloadProtectionMode.ENVELOPE.create(() -> sync);
      var nativeProtector = PayloadProtectionMode.ENVELOPE.createAsync(() -> async);
      String stored =
          nativeProtector.protect("é\u0000tail", "context", "row").toCompletableFuture().join();
      assertEquals("é\u0000tail", imperative.reveal(stored, "context", "row"));
      assertEquals(
          "é\u0000tail",
          nativeProtector
              .reveal(imperative.protect("é\u0000tail", "context", "row"), "context", "row")
              .toCompletableFuture()
              .join());
      byte[] input = new byte[] {0, 1, 2, (byte) 255};
      byte[] encrypted =
          nativeProtector.protect(input, "context", "row").toCompletableFuture().join();
      assertArrayEquals(new byte[] {0, 1, 2, (byte) 255}, input);
      assertArrayEquals(
          input, nativeProtector.reveal(encrypted, "context", "row").toCompletableFuture().join());
    }
  }

  @Test
  void identityModeCopiesBytesAndRejectsReservedUnknownVersionsAndMalformedText() {
    assertEquals(
        PayloadProtector.NONE,
        PayloadProtectionMode.NONE.create(
            () -> {
              throw new AssertionError("Disabled encryption must not acquire a provider");
            }));
    assertEquals(
        AsyncPayloadProtector.NONE,
        PayloadProtectionMode.NONE.createAsync(
            () -> {
              throw new AssertionError("Disabled encryption must not acquire a provider");
            }));
    byte[] original = {1, 2, 3};
    byte[] copy = PayloadProtector.NONE.protect(original, "context", "row");
    copy[0] = 5;
    assertEquals(1, original[0]);
    assertEquals("plain", PayloadProtector.NONE.reveal("plain", "context", "row"));
    assertEquals(
        "plain",
        AsyncPayloadProtector.NONE.reveal("plain", "context", "row").toCompletableFuture().join());
    assertThrows(
        CryptoException.class, () -> PayloadProtector.NONE.reveal("axzp2:abc", "context", "row"));
    assertThrows(
        CryptoException.class,
        () -> PayloadProtector.NONE.reveal(new byte[] {'A', 'X', 'Z', 'P', 99}, "context", "row"));
    assertThrows(
        CryptoException.class, () -> PayloadProtector.NONE.protect("\ud800", "context", "row"));
    assertThrows(
        CryptoException.class, () -> PayloadProtector.NONE.protect("value", "context", ""));
    assertThrows(CryptoException.class, () -> PayloadProtection.parse("axzp1:AAAA="));
    assertThrows(
        CryptoException.class,
        () -> PayloadProtection.unframe(new byte[] {'A', 'X', 'Z', 'P', 2, 0}));
  }
}

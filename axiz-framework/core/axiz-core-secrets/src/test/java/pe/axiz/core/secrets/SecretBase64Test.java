package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretBase64Test {
  @Test
  void externalMonitorsCannotBlockOwnedSecretOperations() throws Exception {
    try (var secret = SecretValue.of(Base64.getEncoder().encodeToString(new byte[32]));
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      synchronized (secret) {
        executor
            .submit(
                () -> {
                  assertArrayEquals(new byte[32], secret.base64Bytes(32, 32));
                  assertEquals(44, secret.revealChars().length);
                  assertEquals(44, secret.reveal().length());
                  secret.close();
                })
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
      }
      assertThrows(IllegalStateException.class, secret::reveal);
    }
  }

  @Test
  void decodedBuffersHaveIndependentOwnershipAndTheSourceCanBeClosed() {
    byte[] expected = new byte[32];
    Arrays.fill(expected, (byte) 21);
    var secret = SecretValue.of(Base64.getEncoder().encodeToString(expected));
    byte[] first = secret.base64Bytes(32, 32);
    Arrays.fill(first, (byte) 0);
    assertArrayEquals(expected, secret.base64Bytes(32, 32));
    secret.close();
    assertThrows(IllegalStateException.class, () -> secret.base64Bytes(32, 32));
  }

  @Test
  void malformedOversizedAndUndersizedSecretsFailWithoutEchoingTheirContents() {
    for (String value : List.of("x".repeat(4096), "á".repeat(44), "!".repeat(44), "AA==")) {
      try (var secret = SecretValue.of(value)) {
        var failure =
            assertThrows(IllegalArgumentException.class, () -> secret.base64Bytes(32, 32));
        assertFalse(failure.getMessage().contains(value));
      }
    }
    try (var secret = SecretValue.of("AA==")) {
      assertThrows(IllegalArgumentException.class, () -> secret.base64Bytes(0, 32));
      assertThrows(IllegalArgumentException.class, () -> secret.base64Bytes(32, Integer.MAX_VALUE));
    }
  }
}

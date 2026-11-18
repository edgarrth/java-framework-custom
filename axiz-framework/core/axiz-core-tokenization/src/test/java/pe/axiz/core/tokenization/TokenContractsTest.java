package pe.axiz.core.tokenization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenContractsTest {
  @Test
  void uncertainTokenMutationRequiresReconciliationInsteadOfAutomaticRetry() {
    var failure = TokenErrors.failure(TokenErrors.UNCERTAIN);
    assertEquals("AXZ-TOKEN-0504", failure.code().value());
    assertEquals(504, failure.category().httpStatus());
    assertFalse(failure.category().retryable());
  }

  @Test
  void tokenHasCanonical128BitEncodingAndOnlyExplicitReveal() {
    byte[] maximum = new byte[16];
    Arrays.fill(maximum, (byte) 255);
    var token = Token.fromEntropy(maximum);
    assertEquals("tok_7n42DGM5Tflk9n8mt7Fhc7", token.reveal());
    assertEquals(token, Token.parse(token.reveal()));
    assertEquals("tok_0000000000000000000000", Token.fromEntropy(new byte[16]).reveal());
    assertFalse(token.toString().contains(token.reveal()));
    assertThrows(RuntimeException.class, () -> Token.parse("tok_zzzzzzzzzzzzzzzzzzzzzz"));
    assertThrows(RuntimeException.class, () -> Token.parse("tok_7n42DGM5Tflk9n8mt7Fhc8"));
    assertThrows(RuntimeException.class, () -> Token.parse("tok_000000000000000000000"));
    assertThrows(RuntimeException.class, () -> Token.fromEntropy(new byte[17]));
  }

  @Test
  void sensitiveContainerOwnsCopiesAndWipesCallbackArrayOnSuccessAndFailure() {
    char[] input = "00883344".toCharArray();
    SensitiveValue value = SensitiveValue.of(input);
    Arrays.fill(input, 'x');
    char[][] captured = {new char[0]};
    try (var copy = value.copy()) {
      assertEquals("00883344", value.reveal());
      assertEquals(
          "00883344",
          value.use(
              chars -> {
                captured[0] = chars;
                return new String(chars);
              }));
      assertArrayEquals(new char[8], captured[0]);
      assertThrows(
          IllegalStateException.class,
          () ->
              value.use(
                  chars -> {
                    captured[0] = chars;
                    throw new IllegalStateException("synthetic");
                  }));
      assertArrayEquals(new char[8], captured[0]);
      value.close();
      assertThrows(IllegalStateException.class, value::reveal);
      assertEquals("00883344", copy.reveal());
      assertEquals("SensitiveValue[****]", value.toString());
      assertFalse(Serializable.class.isAssignableFrom(SensitiveValue.class));
      assertFalse(Serializable.class.isAssignableFrom(Token.class));
    }
  }

  @Test
  void textBoundsAreUtf8BytesAndRejectIllFormedUnicode() {
    assertThrows(RuntimeException.class, () -> SensitiveValue.of(""));
    assertThrows(RuntimeException.class, () -> SensitiveValue.of("\uD800"));
    assertThrows(RuntimeException.class, () -> SensitiveValue.of("\uDC00"));
    assertThrows(RuntimeException.class, () -> SensitiveValue.of("a".repeat(8193)));
    assertThrows(RuntimeException.class, () -> SensitiveValue.of("é".repeat(4097)));
    try (var boundary = SensitiveValue.of("😀".repeat(2048))) {
      assertEquals(4096, boundary.reveal().length());
    }
  }

  @Test
  void profilesPreserveOriginalAndLeadingZeroAndDoNotSilentlyNormalize() {
    try (var input = SensitiveValue.of("00883344");
        var canonical = TextTokenProfiles.dniPe().canonicalize(input)) {
      assertEquals("00883344", canonical.reveal());
    }
    try (var input = SensitiveValue.of(" 00-883344 ")) {
      assertThrows(RuntimeException.class, () -> TextTokenProfiles.dniPe().canonicalize(input));
      try (var canonical = TextTokenProfiles.identity().canonicalize(input)) {
        assertEquals(input.reveal(), canonical.reveal());
      }
    }
  }

  @Test
  void publicResultsFreezeMetadataAndDoNotEchoItInToString() {
    var mutable = new HashMap<>(Map.of("last4", "1234"));
    var result =
        new TokenizationResult(
            Token.fromEntropy(new byte[16]), new TokenType("PAN"), true, mutable);
    mutable.put("last4", "9999");
    assertEquals("1234", result.metadata().get("last4"));
    assertThrows(UnsupportedOperationException.class, () -> result.metadata().clear());
    assertFalse(result.toString().contains("1234"));
    assertThrows(
        RuntimeException.class, () -> TokenMetadata.snapshot(Map.of("x", "v".repeat(257))));
    assertThrows(
        RuntimeException.class, () -> TokenMetadata.snapshot(Map.of("x", "line\ncontrol")));
    assertFalse(TokenizerCapabilities.vault().acceptsOperationId());
  }
}

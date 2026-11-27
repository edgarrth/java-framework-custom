package pe.axiz.core.tokenization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.RateLimitedException;

class TokenBoundaryTest {
  @Test
  void publicErrorsPreserveOnlyTheClosedCatalogAndRetryBudget() {
    for (var definition :
        List.of(
            TokenErrors.INVALID,
            TokenErrors.DENIED,
            TokenErrors.NOT_FOUND,
            TokenErrors.LIMITED,
            TokenErrors.UNSUPPORTED,
            TokenErrors.UNAVAILABLE,
            TokenErrors.UNCERTAIN)) {
      var raw =
          new BusinessException(
              definition, "private provider message", Map.of("pan", "private-field"));
      var exposed = TokenErrors.sanitize(new CompletionException(raw));
      assertEquals(definition.code(), exposed.code());
      assertEquals(definition.title(), exposed.getMessage());
      assertNull(exposed.getCause());
      assertFalse(exposed.toString().contains("private"));
    }
    var limited = TokenErrors.sanitize(TokenErrors.limited(Duration.ofSeconds(7)));
    assertEquals(7, ((RateLimitedException) limited).retryAfterSeconds());
    var unknown =
        new BusinessException(
            ErrorDefinition.of(
                "BANK-PRIVATE-0400", ErrorCategory.BAD_REQUEST, "provider diagnostic"),
            "private provider message");
    assertEquals(TokenErrors.UNAVAILABLE.code(), TokenErrors.sanitize(unknown).code());
    var cycle = new IllegalStateException("private cyclic cause");
    var next = new IllegalStateException("private next", cycle);
    cycle.initCause(next);
    assertEquals(TokenErrors.UNAVAILABLE.code(), TokenErrors.sanitize(cycle).code());
  }

  @Test
  void namespacesHaveBoundedUnambiguousAlphabetsAndDoNotNormalize() {
    assertEquals("A".repeat(32), new TokenType("A".repeat(32)).value());
    assertEquals("DNI_PE-2", new TokenType("DNI_PE-2").value());
    for (String invalid : List.of("", "dni", "1PAN", "PAN ", "A".repeat(33), "PAN\n"))
      assertThrows(BusinessException.class, () -> new TokenType(invalid));
    assertThrows(BusinessException.class, () -> new TokenType(null));
    assertEquals("0".repeat(64), new TokenDomain("0".repeat(64)).value());
    assertEquals("cards.issuer-1_v2", new TokenDomain("cards.issuer-1_v2").value());
    for (String invalid : List.of("", ".cards", "cards/1", "A".repeat(65), "cards\n"))
      assertThrows(BusinessException.class, () -> new TokenDomain(invalid));
    assertThrows(BusinessException.class, () -> new TokenDomain(null));
  }

  @Test
  void metadataLimitsIncludeEntryCountKeyAlphabetNullsAndControlCharacters() {
    var values = new HashMap<String, String>();
    for (int i = 0; i < 16; i++) values.put("field" + i, "v");
    assertEquals(16, TokenMetadata.snapshot(values).size());
    values.put("extra", "v");
    assertThrows(BusinessException.class, () -> TokenMetadata.snapshot(values));
    assertEquals(
        "v".repeat(256),
        TokenMetadata.snapshot(Map.of("K".repeat(32), "v".repeat(256))).get("K".repeat(32)));
    for (String key : List.of("", "0key", "key.space", "K".repeat(33)))
      assertThrows(BusinessException.class, () -> TokenMetadata.snapshot(Map.of(key, "v")));
    var nullKey = new HashMap<String, String>();
    nullKey.put(null, "v");
    assertThrows(BusinessException.class, () -> TokenMetadata.snapshot(nullKey));
    var nullValue = new HashMap<String, String>();
    nullValue.put("k", null);
    assertThrows(BusinessException.class, () -> TokenMetadata.snapshot(nullValue));
    assertThrows(BusinessException.class, () -> TokenMetadata.snapshot(Map.of("k", "\u007f")));
  }

  @Test
  void unicodeAccountingAcceptsEachExactEncodingWidthAndRejectsMalformedPairs() {
    for (String accepted :
        List.of(
            "a".repeat(8192),
            "é".repeat(4096),
            "漢".repeat(2730) + "ab",
            "😀".repeat(2048),
            "\u007f",
            "\u07ff",
            "\u0800")) {
      try (var value = SensitiveValue.of(accepted)) {
        assertEquals(accepted, value.reveal());
        assertEquals("****", value.masked());
      }
    }
    for (String rejected : List.of("漢".repeat(2731), "\uD800x", "x\uD800", "\uDC00x"))
      assertThrows(BusinessException.class, () -> SensitiveValue.of(rejected));
    assertThrows(BusinessException.class, () -> SensitiveValue.of((String) null));
    assertThrows(BusinessException.class, () -> SensitiveValue.of(new char[8193]));
  }

  @Test
  void dniProfileHasVersionedIdentityAndRejectsNonAsciiOrShortInputs() {
    var dni = TextTokenProfiles.dniPe();
    assertEquals("dni.pe", dni.id());
    assertEquals(1, dni.version());
    assertEquals("text.identity", TextTokenProfiles.identity().id());
    assertEquals(1, TextTokenProfiles.identity().version());
    for (String text : List.of("09999999", "00000000", "99999999")) {
      try (var input = SensitiveValue.of(text);
          var output = dni.canonicalize(input)) {
        assertEquals(text, output.reveal());
      }
    }
    for (String text : List.of("1234567", "123456789", "/1234567", ":1234567", "１２３４５６７８")) {
      try (var input = SensitiveValue.of(text)) {
        assertThrows(BusinessException.class, () -> dni.canonicalize(input));
      }
    }
  }

  @Test
  void publicDescriptorsSnapshotMetadataAndTokenIdentityWorksInIndexes() {
    var token = Token.fromEntropy(new byte[16]);
    var otherBytes = new byte[16];
    otherBytes[15] = 1;
    var other = Token.fromEntropy(otherBytes);
    var tokens = new HashSet<Token>();
    tokens.add(token);
    tokens.add(Token.parse(token.reveal()));
    tokens.add(other);
    assertEquals(2, tokens.size());
    assertEquals(token.hashCode(), Token.parse(token.reveal()).hashCode());
    assertNotEquals(token, other);
    assertNotEquals(token, token.reveal());
    assertEquals("tok_******************0000", token.masked());
    assertThrows(BusinessException.class, () -> Token.parse(null));
    assertThrows(BusinessException.class, () -> Token.parse("bad_0000000000000000000000"));
    assertThrows(BusinessException.class, () -> Token.fromEntropy(new byte[15]));
    var metadata = new HashMap<>(Map.of("label", "private-metadata"));
    var descriptor = new TokenDescriptor(token, new TokenType("DNI"), metadata);
    var result = new TokenizationResult(token, new TokenType("DNI"), true, metadata);
    metadata.clear();
    assertEquals("private-metadata", descriptor.metadata().get("label"));
    assertThrows(UnsupportedOperationException.class, () -> descriptor.metadata().clear());
    assertEquals(descriptor.metadata(), result.metadata());
    assertThrows(UnsupportedOperationException.class, () -> result.metadata().clear());
    assertFalse(result.toString().contains("private-metadata"));
    assertFalse(descriptor.toString().contains("private-metadata"));
    assertFalse(descriptor.toString().contains(token.reveal()));
    assertTrue(descriptor.toString().contains("DNI"));
  }
}

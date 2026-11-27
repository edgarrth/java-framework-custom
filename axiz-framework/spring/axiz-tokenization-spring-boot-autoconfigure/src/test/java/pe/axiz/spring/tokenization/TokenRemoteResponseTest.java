package pe.axiz.spring.tokenization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;

class TokenRemoteResponseTest {
  private static final TokenType DNI = new TokenType("DNI");

  private static HttpHeaders headers() {
    var headers = new HttpHeaders();
    headers.set("Content-Type", "application/json");
    headers.set("Cache-Control", "no-store");
    return headers;
  }

  @Test
  void unexpectedFieldsTypesAndUnicodeNeverBecomeSensitiveResults() {
    for (String json :
        List.of(
            "{\"type\":\"PAN\",\"value\":\"poison-secret\"}",
            "{\"type\":\"DNI\",\"type\":\"DNI\",\"value\":\"poison-secret\"}",
            "{\"type\":\"DNI\",\"value\":\"poison-secret\",\"index\":\"leak\"}",
            "{\"type\":\"DNI\",\"value\":\"\\uD800\"}")) {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      try (var reply = new TokenRemoteResponse(200, headers(), bytes, false)) {
        var failure = assertThrows(BusinessException.class, () -> reply.revealed(DNI));
        assertEquals(TokenErrors.UNAVAILABLE.code(), failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("poison-secret"));
      }
      for (byte value : bytes) assertEquals(0, value);
    }
    try (var reply =
        new TokenRemoteResponse(200, headers(), new byte[] {(byte) 0xc3, 0x28}, false)) {
      assertThrows(BusinessException.class, () -> reply.revealed(DNI));
    }
  }

  @Test
  void missingMutationAcknowledgmentIsUncertainAndNoStoreIsMandatory() {
    try (var reply = new TokenRemoteResponse(204, headers(), new byte[0], true)) {
      assertEquals(
          TokenErrors.UNCERTAIN.code(),
          assertThrows(BusinessException.class, reply::deleted).code());
    }
    var headers = headers();
    headers.remove(HttpHeaders.CACHE_CONTROL);
    String body =
        "{\"token\":\"" + Token.fromEntropy(new byte[16]).reveal() + "\",\"type\":\"DNI\"}";
    try (var reply =
        new TokenRemoteResponse(200, headers, body.getBytes(StandardCharsets.UTF_8), false)) {
      assertThrows(BusinessException.class, () -> reply.lookup(DNI));
    }
  }
}

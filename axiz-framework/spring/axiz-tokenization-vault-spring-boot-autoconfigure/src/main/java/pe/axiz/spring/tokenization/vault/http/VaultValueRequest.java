package pe.axiz.spring.tokenization.vault.http;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;

/** Dedicated request reader. Owns the submitted value; never accepts caller identity fields. */
record VaultValueRequest(
    TokenOperation operation,
    TokenType type,
    TokenDomain domain,
    @Nullable SensitiveValue sensitive,
    @Nullable Token reference,
    @Nullable String justification,
    @Nullable String correlation)
    implements AutoCloseable {
  static VaultValueRequest read(TokenOperation operation, byte[] body) {
    try {
      if (body.length == 0 || body.length > VaultHttpPolicy.MAX_BODY)
        throw TokenErrors.failure(TokenErrors.INVALID);
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      var fields = JsonReader.parseObject(json);
      Set<String> expected =
          switch (operation) {
            case TOKENIZE -> Set.of("type", "domain", "value", "operationId");
            case LOOKUP -> Set.of("type", "domain", "value", "purpose");
            case DETOKENIZE -> Set.of("type", "domain", "token", "purpose");
            case DESCRIBE, DELETE -> Set.of("type", "domain", "token");
          };
      if (!fields.keySet().equals(expected)) throw TokenErrors.failure(TokenErrors.INVALID);
      var type = new TokenType(text(fields, "type", 32));
      var domain = new TokenDomain(text(fields, "domain", 64));
      Token token = fields.containsKey("token") ? Token.parse(text(fields, "token", 26)) : null;
      String purpose = fields.containsKey("purpose") ? text(fields, "purpose", 64) : null;
      String operationId =
          fields.containsKey("operationId") ? text(fields, "operationId", 36) : null;
      if (operationId != null && !UUID.fromString(operationId).toString().equals(operationId))
        throw TokenErrors.failure(TokenErrors.INVALID);
      var value =
          fields.containsKey("value")
              ? SensitiveValue.of(text(fields, "value", SensitiveValue.MAX_UTF8_BYTES))
              : null;
      return new VaultValueRequest(operation, type, domain, value, token, purpose, operationId);
    } catch (RuntimeException | CharacterCodingException failure) {
      throw TokenErrors.failure(TokenErrors.INVALID);
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  private static String text(Map<String, @Nullable Object> fields, String key, int limit) {
    if (!(fields.get(key) instanceof String value) || value.isEmpty() || value.length() > limit)
      throw TokenErrors.failure(TokenErrors.INVALID);
    return value;
  }

  SensitiveValue value() {
    return Objects.requireNonNull(sensitive);
  }

  Token token() {
    return Objects.requireNonNull(reference);
  }

  String purpose() {
    return Objects.requireNonNull(justification);
  }

  String operationId() {
    return Objects.requireNonNull(correlation);
  }

  @Override
  public void close() {
    if (sensitive != null) sensitive.close();
  }

  @Override
  public String toString() {
    return "VaultValueRequest[****]";
  }
}

package pe.axiz.spring.tokenization;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;

/**
 * Límite de solicitud dedicado. Nunca registra un serializador que revela valores en el mapper de
 * la aplicación.
 */
final class TokenValueRequestWriter implements AutoCloseable {
  private final TokenOperation operation;
  private final TokenType type;
  private final byte[] bytes;

  private TokenValueRequestWriter(TokenOperation operation, TokenType type, byte[] bytes) {
    this.operation = operation;
    this.type = type;
    this.bytes = bytes;
  }

  static TokenValueRequestWriter value(
      TokenOperation operation,
      TokenType type,
      SensitiveValue value,
      TokenDomain domain,
      String reason) {
    var fields = fields(type, domain);
    if (operation == TokenOperation.TOKENIZE) {
      if (reason.length() != 36 || !UUID.fromString(reason).toString().equals(reason))
        throw TokenErrors.failure(TokenErrors.INVALID);
      fields.put("operationId", reason);
    } else {
      validatePurpose(reason);
      fields.put("purpose", reason);
    }
    fields.put("value", value.reveal());
    return new TokenValueRequestWriter(
        operation, type, JsonWriter.write(fields).getBytes(StandardCharsets.UTF_8));
  }

  static TokenValueRequestWriter token(
      TokenOperation operation,
      TokenType type,
      Token token,
      TokenDomain domain,
      @Nullable String purpose) {
    var fields = fields(type, domain);
    fields.put("token", token.reveal());
    if (purpose != null) {
      validatePurpose(purpose);
      fields.put("purpose", purpose);
    }
    return new TokenValueRequestWriter(
        operation, type, JsonWriter.write(fields).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> fields(TokenType type, TokenDomain domain) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("type", type.value());
    fields.put("domain", domain.value());
    return fields;
  }

  private static void validatePurpose(String purpose) {
    if (!purpose.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,63}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
  }

  String path() {
    return switch (operation) {
      case TOKENIZE -> "/v1/tokens";
      case LOOKUP -> "/v1/tokens/lookup";
      case DESCRIBE -> "/v1/tokens/describe";
      case DETOKENIZE -> "/v1/tokens/reveal";
      case DELETE -> "/v1/tokens/delete";
    };
  }

  boolean mutates() {
    return operation == TokenOperation.TOKENIZE || operation == TokenOperation.DELETE;
  }

  TokenType type() {
    return type;
  }

  byte[] bytes() {
    return bytes;
  }

  @Override
  public void close() {
    Arrays.fill(bytes, (byte) 0);
  }

  @Override
  public String toString() {
    return "TokenValueRequestWriter[****]";
  }
}

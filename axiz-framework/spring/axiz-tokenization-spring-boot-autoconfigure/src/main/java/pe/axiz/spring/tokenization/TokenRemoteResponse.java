package pe.axiz.spring.tokenization;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;

/** Bounded closed protocol decoder. Remote messages, payloads and causes never escape as errors. */
final class TokenRemoteResponse implements AutoCloseable {
  static final int MAX_BYTES = 65_536;
  private final int status;
  private final HttpHeaders headers;
  private final byte[] body;
  private final boolean mutation;

  TokenRemoteResponse(int status, HttpHeaders headers, byte[] body, boolean mutation) {
    this.status = status;
    this.headers = headers;
    this.body = body;
    this.mutation = mutation;
  }

  TokenizationResult tokenized(TokenType type) {
    check();
    var data = json(Set.of("token", "type", "created", "metadata"), type);
    if (!(data.get("created") instanceof Boolean created) || status != (created ? 201 : 200))
      throw invalid();
    return new TokenizationResult(
        Token.parse(string(data, "token")), type, created, metadata(data));
  }

  Optional<Token> lookup(TokenType type) {
    if (absent()) return Optional.empty();
    checkStatus(200);
    var data = json(Set.of("token", "type"), type);
    return valid(() -> Optional.of(Token.parse(string(data, "token"))));
  }

  Optional<TokenDescriptor> describe(TokenType type) {
    if (absent()) return Optional.empty();
    checkStatus(200);
    var data = json(Set.of("token", "type", "metadata"), type);
    return Optional.of(
        new TokenDescriptor(Token.parse(string(data, "token")), type, metadata(data)));
  }

  SensitiveValue revealed(TokenType type) {
    checkStatus(200);
    var data = json(Set.of("type", "value"), type);
    return valid(() -> SensitiveValue.of(string(data, "value")));
  }

  boolean deleted() {
    checkStatus(204);
    var deleted = headers.getFirst("X-Axiz-Token-Deleted");
    if (body.length != 0 || !("true".equals(deleted) || "false".equals(deleted))) throw invalid();
    return Boolean.parseBoolean(deleted);
  }

  private boolean absent() {
    if (status != 404) return false;
    // A proxy's generic 404 is not evidence that the value/token is absent.
    if (!TokenErrors.NOT_FOUND.code().value().equals(errorCode())) throw invalid();
    return true;
  }

  private void checkStatus(int expected) {
    check();
    if (status != expected) throw invalid();
  }

  private void check() {
    if (body.length > MAX_BYTES) throw invalid();
    if (status >= 400) {
      String code = errorCode();
      for (var definition :
          List.of(
              TokenErrors.INVALID,
              TokenErrors.DENIED,
              TokenErrors.NOT_FOUND,
              TokenErrors.LIMITED,
              TokenErrors.UNSUPPORTED,
              TokenErrors.UNAVAILABLE,
              TokenErrors.UNCERTAIN)) {
        if (!definition.code().value().equals(code)) continue;
        if (definition.category().httpStatus() != status) throw invalid();
        if (definition.equals(TokenErrors.LIMITED))
          throw TokenErrors.limited(Duration.ofSeconds(retryAfter()));
        throw TokenErrors.failure(definition);
      }
      if (status == 401 || status == 403) throw TokenErrors.failure(TokenErrors.DENIED);
      throw invalid();
    }
    if (status < 200 || status >= 300) throw invalid();
    String cache = headers.getCacheControl();
    if (cache == null
        || Arrays.stream(cache.split(","))
            .noneMatch(part -> part.strip().equalsIgnoreCase("no-store"))) throw invalid();
  }

  private long retryAfter() {
    var raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (raw == null || !raw.matches("[0-9]{1,5}")) return 1;
    return Math.clamp(Long.parseLong(raw), 1, 86_400);
  }

  private String errorCode() {
    try {
      return string(read("application/problem+json"), "code");
    } catch (RuntimeException ignored) {
      return "";
    }
  }

  private Map<String, @Nullable Object> json(Set<String> fields, TokenType type) {
    var data = read("application/json");
    if (!data.keySet().equals(fields) || !type.value().equals(data.get("type"))) throw invalid();
    return data;
  }

  private Map<String, @Nullable Object> read(String expectedType) {
    if (body.length == 0 || body.length > MAX_BYTES) throw invalid();
    MediaType type = headers.getContentType();
    if (type == null
        || !MediaType.parseMediaType(expectedType).isCompatibleWith(type)
        || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))
        || headers.containsHeader(HttpHeaders.CONTENT_ENCODING)) throw invalid();
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      return JsonReader.parseObject(json);
    } catch (CharacterCodingException | IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private Map<String, String> metadata(Map<String, @Nullable Object> data) {
    if (!(data.get("metadata") instanceof Map<?, ?> raw) || raw.size() > 16) throw invalid();
    var metadata = new LinkedHashMap<String, String>();
    raw.forEach(
        (key, value) -> {
          if (!(key instanceof String name) || !(value instanceof String text)) throw invalid();
          metadata.put(name, text);
        });
    return metadata;
  }

  private String string(Map<String, @Nullable Object> data, String name) {
    if (!(data.get(name) instanceof String value)) throw invalid();
    return value;
  }

  private <T> T valid(Supplier<T> decode) {
    try {
      return decode.get();
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private BusinessException invalid() {
    return TokenErrors.failure(mutation ? TokenErrors.UNCERTAIN : TokenErrors.UNAVAILABLE);
  }

  @Override
  public void close() {
    Arrays.fill(body, (byte) 0);
  }

  @Override
  public String toString() {
    return "TokenRemoteResponse[****]";
  }
}

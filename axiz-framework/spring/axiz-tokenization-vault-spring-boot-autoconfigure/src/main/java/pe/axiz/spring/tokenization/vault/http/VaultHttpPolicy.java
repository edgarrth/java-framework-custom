package pe.axiz.spring.tokenization.vault.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.api.HttpPayloadPolicy;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;

/** Política fija de rutas y transporte compartida por servlet, reactivo, logging e idempotencia. */
final class VaultHttpPolicy implements HttpPayloadPolicy {
  static final int MAX_BODY = 65536;
  private static final PathPattern ROOT = PathPatternParser.defaultInstance.parse("/v1/tokens");
  private static final PathPattern CHILDREN =
      PathPatternParser.defaultInstance.parse("/v1/tokens/{*rest}");
  private static final Map<PathPattern, TokenOperation> OPERATIONS =
      Map.of(
          ROOT,
          TokenOperation.TOKENIZE,
          route("lookup"),
          TokenOperation.LOOKUP,
          route("describe"),
          TokenOperation.DESCRIBE,
          route("reveal"),
          TokenOperation.DETOKENIZE,
          route("delete"),
          TokenOperation.DELETE);
  private final boolean allowInsecure;

  VaultHttpPolicy(boolean allowInsecure) {
    this.allowInsecure = allowInsecure;
  }

  private static PathPattern route(String suffix) {
    return PathPatternParser.defaultInstance.parse("/v1/tokens/" + suffix);
  }

  boolean protectedPath(String path) {
    var parsed = PathContainer.parsePath(path);
    return ROOT.matches(parsed) || CHILDREN.matches(parsed);
  }

  @Override
  public boolean permitsCapture(String path) {
    return !protectedPath(path);
  }

  TokenOperation operation(String path) {
    var parsed = PathContainer.parsePath(path);
    return OPERATIONS.entrySet().stream()
        .filter(e -> e.getKey().matches(parsed))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow(() -> TokenErrors.failure(TokenErrors.INVALID));
  }

  void transport(boolean secure) {
    if (!secure && !allowInsecure) throw TokenErrors.failure(TokenErrors.DENIED);
  }

  static void requestHeaders(
      @Nullable String contentType,
      @Nullable String encoding,
      @Nullable String query,
      @Nullable String idempotencyKey,
      long length) {
    try {
      if (contentType == null
          || query != null
          || idempotencyKey != null
          || encoding != null
          || length > MAX_BODY) throw TokenErrors.failure(TokenErrors.INVALID);
      var media = MediaType.parseMediaType(contentType);
      if (!"application".equals(media.getType())
          || !"json".equals(media.getSubtype())
          || (media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset())))
        throw TokenErrors.failure(TokenErrors.INVALID);
    } catch (RuntimeException invalid) {
      throw TokenErrors.failure(TokenErrors.INVALID);
    }
  }

  static void noStore(HttpHeaders headers) {
    headers.setCacheControl("no-store");
    headers.setPragma("no-cache");
    headers.setExpires(0);
    headers.set("X-Content-Type-Options", "nosniff");
  }
}

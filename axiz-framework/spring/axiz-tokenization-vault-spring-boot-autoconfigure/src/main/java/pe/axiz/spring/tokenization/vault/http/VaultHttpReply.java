package pe.axiz.spring.tokenization.vault.http;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import pe.axiz.core.errors.ErrorCatalog;
import pe.axiz.core.errors.ErrorMessageResolver;
import pe.axiz.core.errors.ProblemFactory;
import pe.axiz.core.errors.ProblemJson;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.tokenization.TokenErrors;

/**
 * Owned bytes written only by the dedicated endpoint, with cleanup on completion or cancellation.
 */
final class VaultHttpReply implements AutoCloseable {
  private boolean deleted;
  private final int status;
  private final String contentType;
  private final long retryAfterSeconds;
  private final byte[] body;

  private VaultHttpReply(int status, String contentType, long retryAfterSeconds, byte[] body) {
    this.status = status;
    this.contentType = contentType;
    this.retryAfterSeconds = retryAfterSeconds;
    this.body = body;
  }

  int status() {
    return status;
  }

  String contentType() {
    return contentType;
  }

  long retryAfterSeconds() {
    return retryAfterSeconds;
  }

  byte[] body() {
    return body;
  }

  private static final ProblemFactory ERRORS =
      new ProblemFactory(
          ErrorCatalog.defaults(),
          ErrorMessageResolver.none(),
          List.of(),
          ProblemFactory.Settings.defaults(),
          Clock.systemUTC());

  static VaultHttpReply json(int status, Map<String, Object> value) {
    return new VaultHttpReply(
        status, "application/json", 0, JsonWriter.write(value).getBytes(StandardCharsets.UTF_8));
  }

  static VaultHttpReply deleted(boolean deleted) {
    var reply = new VaultHttpReply(204, "application/json", 0, new byte[0]);
    reply.deleted = deleted;
    return reply;
  }

  boolean deleted() {
    return deleted;
  }

  static VaultHttpReply error(Throwable failure) {
    var safe = TokenErrors.sanitize(failure);
    var problem = ERRORS.create(safe, Locale.ROOT, null, null);
    return new VaultHttpReply(
        problem.status(),
        "application/problem+json",
        safe instanceof RateLimitedException limited ? limited.retryAfterSeconds() : 0,
        ProblemJson.write(problem).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void close() {
    Arrays.fill(body, (byte) 0);
  }

  @Override
  public String toString() {
    return "VaultHttpReply[****]";
  }
}

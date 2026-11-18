package pe.axiz.spring.tokenization.vault.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.security.AuthenticatedAccess;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.spring.tokenization.vault.VerifiedTokenCaller;

/** Dedicated servlet boundary; no general JSON mapper sees submitted or revealed values. */
@RestController
@AuthenticatedAccess
public class ServletVaultController {
  private final VaultOperations engine;
  private final VerifiedTokenCaller callers;
  private final VaultHttpPolicy policy;

  ServletVaultController(
      VaultOperations engine, VerifiedTokenCaller callers, VaultHttpPolicy policy) {
    this.engine = engine;
    this.callers = callers;
    this.policy = policy;
  }

  @PostMapping({
    "/v1/tokens",
    "/v1/tokens/lookup",
    "/v1/tokens/describe",
    "/v1/tokens/reveal",
    "/v1/tokens/delete"
  })
  public void execute(
      HttpServletRequest request, HttpServletResponse response, @Nullable Principal principal)
      throws IOException {
    VaultHttpReply answer;
    try {
      policy.transport(request.isSecure());
      if (!(principal instanceof Authentication authentication))
        throw TokenErrors.failure(TokenErrors.DENIED);
      var caller = callers.resolve(authentication); // Before reading any submitted value.
      VaultHttpPolicy.requestHeaders(
          request.getContentType(),
          request.getHeader(HttpHeaders.CONTENT_ENCODING),
          request.getQueryString(),
          request.getHeader("Idempotency-Key"),
          request.getContentLengthLong());
      var operation =
          policy.operation(request.getRequestURI().substring(request.getContextPath().length()));
      try (var input = request.getInputStream();
          var decoded =
              VaultValueRequest.read(operation, input.readNBytes(VaultHttpPolicy.MAX_BODY + 1))) {
        answer = VaultHttpEndpoint.execute(engine, caller, decoded);
      }
    } catch (IOException failure) {
      answer = VaultHttpReply.error(TokenErrors.failure(TokenErrors.INVALID));
    } catch (RuntimeException failure) {
      answer = VaultHttpReply.error(failure);
    }
    try (var reply = answer) {
      write(response, reply);
    }
  }

  static void write(HttpServletResponse response, VaultHttpReply reply) throws IOException {
    response.setStatus(reply.status());
    var headers = new HttpHeaders();
    VaultHttpPolicy.noStore(headers);
    headers.forEach((key, values) -> response.setHeader(key, values.getFirst()));
    if (reply.retryAfterSeconds() > 0)
      response.setHeader("Retry-After", Long.toString(reply.retryAfterSeconds()));
    if (reply.status() == 204)
      response.setHeader("X-Axiz-Token-Deleted", Boolean.toString(reply.deleted()));
    if (reply.status() != 204) {
      response.setContentType(reply.contentType());
      response.setContentLength(reply.body().length);
      response.getOutputStream().write(reply.body());
    }
  }
}

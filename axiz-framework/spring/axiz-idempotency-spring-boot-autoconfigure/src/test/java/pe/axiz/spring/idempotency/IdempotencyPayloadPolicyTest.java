package pe.axiz.spring.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IdempotencyPayloadPolicyTest {
  @Test
  void servletVetoPassesOriginalBodyWithoutFingerprintOrResponseCapture() throws Exception {
    var service = mock(IdempotencyService.class);
    var filter = new IdempotencyFilter(service, 1, 1, List.of(path -> !path.startsWith("/vault")));
    var request = new MockHttpServletRequest("POST", "/app/vault/reveal");
    request.setContextPath("/app");
    request.addHeader("Idempotency-Key", "key");
    request.setContent("synthetic-sensitive-body".getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (actualRequest, actualResponse) -> {
          assertThat(actualRequest).isSameAs(request);
          assertThat(actualResponse).isSameAs(response);
          assertThat(actualRequest.getAttribute(IdempotencySupport.FINGERPRINT_ATTRIBUTE)).isNull();
          assertThat(actualRequest.getInputStream().readAllBytes())
              .isEqualTo(request.getContentAsByteArray());
        });
    verifyNoInteractions(service);
  }

  @Test
  void reactiveVetoDoesNotSubscribeToBodyOrDecorateExchangeEvenWithIdempotencyHeader() {
    var service = mock(AsyncIdempotencyService.class);
    var filter =
        new IdempotencyWebFilter(service, 1, 1, List.of(path -> !path.startsWith("/vault")));
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/vault/reveal")
                .header("Idempotency-Key", "key")
                .body("synthetic-sensitive-body"));
    StepVerifier.create(
            filter.filter(
                exchange,
                actual -> {
                  assertThat(actual).isSameAs(exchange);
                  return Mono.empty();
                }))
        .verifyComplete();
    verifyNoInteractions(service);
  }
}

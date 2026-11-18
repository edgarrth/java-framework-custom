package pe.axiz.spring.tokenization.vault.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties.Auth;
import pe.axiz.spring.tokenization.vault.VerifiedTokenCaller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Transport tests use verified-authentication doubles; engine access/audit have separate tests. */
class VaultHttpServerTest {
  private static final Token TOKEN = Token.fromEntropy(new byte[16]);
  private static final TokenType DNI = new TokenType("DNI");
  private static final String VALUE_BODY =
      "{\"type\":\"DNI\",\"domain\":\"payments\",\"value\":\"87654321\","
          + "\"operationId\":\"00000000-0000-4000-8000-000000000001\"}";
  private static final String REVEAL_BODY =
      "{\"type\":\"DNI\",\"domain\":\"payments\",\"token\":\""
          + TOKEN.reveal()
          + "\",\"purpose\":\"support\"}";
  private static final VerifiedTokenCaller CALLERS =
      new VerifiedTokenCaller(new Auth("tenant", null, "client_id", "domains", "SCOPE_tokens:"));

  private static JwtAuthenticationToken jwt(String subject) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("synthetic-verified-token")
            .header("alg", "RS256")
            .claim("sub", subject)
            .claim("client_id", "app")
            .claim("tenant", "tenant")
            .claim("domains", List.of("payments"))
            .build(),
        List.of(new SimpleGrantedAuthority("SCOPE_tokens:tokenize")));
  }

  @Test
  void servletWritesActualTransportValuesAndClosesSensitiveInputAndRevelation() throws Exception {
    var engine = mock(VaultOperations.class);
    var received = new AtomicReference<SensitiveValue>();
    when(engine.tokenize(any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              SensitiveValue value = call.getArgument(2);
              assertEquals("87654321", value.reveal());
              received.set(value);
              return new TokenizationResult(TOKEN, DNI, true, Map.of());
            });
    var revealed = SensitiveValue.of("87654321");
    when(engine.detokenize(any(), any(), any(), any(), any())).thenReturn(revealed);
    var policy = new VaultHttpPolicy(false);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ServletVaultController(engine, CALLERS, policy))
            .addFilters(new ServletVaultGuard(policy))
            .build();
    mvc.perform(
            post("/v1/tokens")
                .secure(true)
                .principal(jwt("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALUE_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(
            content()
                .json(
                    "{\"token\":\""
                        + TOKEN.reveal()
                        + "\",\"type\":\"DNI\",\"created\":true,\"metadata\":{}}"));
    assertThrows(IllegalStateException.class, () -> received.get().reveal());
    mvc.perform(
            post("/v1/tokens/reveal")
                .secure(true)
                .principal(jwt("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REVEAL_BODY))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("{\"type\":\"DNI\",\"value\":\"87654321\"}"));
    assertThrows(IllegalStateException.class, revealed::reveal);
  }

  @Test
  void forgedIdentityMalformedOrOversizedBodiesAndInsecureTransportNeverReachEngine()
      throws Exception {
    var engine = mock(VaultOperations.class);
    var policy = new VaultHttpPolicy(false);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ServletVaultController(engine, CALLERS, policy))
            .addFilters(new ServletVaultGuard(policy))
            .build();
    for (String body :
        List.of(
            VALUE_BODY.replace("\"value\":", "\"caller\":\"admin\",\"value\":"),
            VALUE_BODY.replace("\"value\":", "\"tenant\":\"other\",\"value\":"),
            VALUE_BODY.replace("\"value\":", "\"authorities\":[\"admin\"],\"value\":"),
            VALUE_BODY.replace("\"type\":\"DNI\"", "\"type\":\"DNI\",\"type\":\"PAN\""),
            VALUE_BODY.replace("87654321", "x".repeat(8193)),
            " ".repeat(65537))) {
      mvc.perform(
              post("/v1/tokens")
                  .secure(true)
                  .principal(jwt("alice"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    mvc.perform(
            post("/v1/tokens")
                .secure(true)
                .principal(jwt("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[] {(byte) 0xc3, 0x28}))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/v1/tokens")
                .principal(jwt("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALUE_BODY))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            post("/v1/tokens")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALUE_BODY))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/v1/tokens")
                .secure(true)
                .principal(jwt("alice"))
                .header("Idempotency-Key", "unsafe-cache")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALUE_BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/tokens/reveal").secure(true))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(engine);
  }

  @Test
  void publicErrorsPreserveUncertaintyAndRetryDelayWithoutProviderMessages() throws Exception {
    var engine = mock(VaultOperations.class);
    var policy = new VaultHttpPolicy(false);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ServletVaultController(engine, CALLERS, policy))
            .addFilters(new ServletVaultGuard(policy))
            .build();
    for (AxizException failure :
        List.of(
            TokenErrors.failure(TokenErrors.DENIED),
            TokenErrors.limited(Duration.ofSeconds(7)),
            TokenErrors.failure(TokenErrors.UNCERTAIN))) {
      when(engine.detokenize(any(), any(), any(), any(), any())).thenThrow(failure);
      var result =
          mvc.perform(
                  post("/v1/tokens/reveal")
                      .secure(true)
                      .principal(jwt("alice"))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(REVEAL_BODY))
              .andExpect(header().string("Cache-Control", "no-store"))
              .andReturn();
      assertThat(result.getResponse().getContentAsString())
          .contains(failure.code().value())
          .doesNotContain("87654321");
      if (failure.code().equals(TokenErrors.LIMITED.code()))
        assertEquals("7", result.getResponse().getHeader("Retry-After"));
      if (failure.code().equals(TokenErrors.UNCERTAIN.code()))
        assertEquals(504, result.getResponse().getStatus());
      reset(engine);
    }
    when(engine.detokenize(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("synthetic-secret-canary"));
    var response =
        mvc.perform(
                post("/v1/tokens/reveal")
                    .secure(true)
                    .principal(jwt("alice"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REVEAL_BODY))
            .andExpect(status().isServiceUnavailable())
            .andReturn()
            .getResponse();
    assertThat(response.getContentAsString()).doesNotContain("canary");
  }

  @Test
  void nativeHttpWritesRealValueAndKeepsCallerIsolatedAcrossSubscriptions() {
    var engine = mock(AsyncVaultOperations.class);
    var subjects = new CopyOnWriteArrayList<String>();
    var inputs = new CopyOnWriteArrayList<SensitiveValue>();
    when(engine.tokenize(any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              subjects.add(call.<TokenCaller>getArgument(0).subject());
              inputs.add(call.getArgument(2));
              assertEquals("87654321", call.<SensitiveValue>getArgument(2).reveal());
              return CompletableFuture.completedFuture(
                  new TokenizationResult(TOKEN, DNI, true, Map.of()));
            });
    var policy = new VaultHttpPolicy(false);
    var controller = new ReactiveVaultController(engine, CALLERS, policy);
    var alice = exchange("/v1/tokens", VALUE_BODY, "alice");
    var bob = exchange("/v1/tokens", VALUE_BODY, "bob");
    StepVerifier.create(Flux.merge(controller.execute(alice), controller.execute(bob)))
        .verifyComplete();
    assertThat(subjects).containsExactlyInAnyOrder("alice", "bob");
    inputs.forEach(value -> assertThrows(IllegalStateException.class, value::reveal));
    assertThat(alice.getResponse().getBodyAsString().block(Duration.ofSeconds(1)))
        .contains(TOKEN.reveal())
        .doesNotContain("87654321");

    var revealed = SensitiveValue.of("87654321");
    when(engine.detokenize(any(), any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(revealed));
    var client =
        WebTestClient.bindToController(controller)
            .webFilter(
                new ReactiveVaultGuard(policy),
                (request, chain) ->
                    chain.filter(request.mutate().principal(Mono.just(jwt("alice"))).build()))
            .configureClient()
            .baseUrl("https://vault.test")
            .build();
    client
        .post()
        .uri("/v1/tokens/reveal")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REVEAL_BODY)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .json("{\"type\":\"DNI\",\"value\":\"87654321\"}");
    assertThrows(IllegalStateException.class, revealed::reveal);
  }

  @Test
  void cancellationClosesLateRevelationsAndParsingFailuresNeverCallNativeEngine() {
    var engine = mock(AsyncVaultOperations.class);
    var pending =
        new CompletableFuture<SensitiveValue>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    when(engine.detokenize(any(), any(), any(), any(), any())).thenReturn(pending);
    var controller = new ReactiveVaultController(engine, CALLERS, new VaultHttpPolicy(false));
    var subscription =
        controller.execute(exchange("/v1/tokens/reveal", REVEAL_BODY, "alice")).subscribe();
    verify(engine).detokenize(any(), any(), any(), any(), any());
    subscription.dispose();
    var late = SensitiveValue.of("87654321");
    pending.complete(late);
    assertThrows(IllegalStateException.class, late::reveal);
    reset(engine);
    var invalid =
        exchange(
            "/v1/tokens", VALUE_BODY.replace("\"value\":", "\"caller\":{},\"value\":"), "alice");
    StepVerifier.create(controller.execute(invalid)).verifyComplete();
    assertEquals(400, invalid.getResponse().getStatusCode().value());
    verifyNoInteractions(engine);
  }

  @Test
  void encodedRoutesAreProtectedAndDeniedCaptureDoesNotAffectOtherCapabilities() {
    var policy = new VaultHttpPolicy(false);
    for (String path :
        List.of(
            "/v1/tokens",
            "/v1/tokens/reveal",
            "/v1/%74okens/reveal",
            "/v1/tokens;ignored=x/reveal")) assertThat(policy.permitsCapture(path)).isFalse();
    assertThat(policy.permitsCapture("/payments")).isTrue();
  }

  private static MockServerWebExchange exchange(String path, String body, String subject) {
    return MockServerWebExchange.builder(
            MockServerHttpRequest.post("https://vault.test" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body))
        .principal(jwt(subject))
        .build();
  }
}

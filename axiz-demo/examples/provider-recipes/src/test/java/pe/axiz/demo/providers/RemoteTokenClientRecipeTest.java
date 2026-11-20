package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.spring.httpclient.AxizHttpClientAutoConfiguration;
import pe.axiz.spring.tokenization.AxizTokenizationRemoteAutoConfiguration;
import pe.axiz.spring.tokenization.ReactiveTokenizer;

/** Consumer wire contract. The loopback peer is a protocol fixture, not a deployable vault. */
class RemoteTokenClientRecipeTest {
  @Test
  void remoteConsumersSendIdentityAndReceiveOnlyOpaqueTokensOnBothStacks(TestReporter evidence)
      throws Exception {
    var type = new TokenType("DNI");
    var domain = new TokenDomain("customer-onboarding");
    var token = Token.fromEntropy(new byte[16]);
    var identities = new ConcurrentLinkedQueue<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/tokens",
        exchange -> {
          try (exchange) {
            identities.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            var request =
                JsonReader.parseObject(
                    new String(
                        exchange.getRequestBody().readNBytes(16384), StandardCharsets.UTF_8));
            assertThat(request).doesNotContainKeys("caller", "tenant", "ciphertext", "hash");
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            String path = exchange.getRequestURI().getPath();
            String body =
                switch (path) {
                  case "/v1/tokens" ->
                      "{\"token\":\""
                          + token.reveal()
                          + "\",\"type\":\"DNI\",\"created\":true,\"metadata\":{}}";
                  case "/v1/tokens/lookup" ->
                      "{\"token\":\"" + token.reveal() + "\",\"type\":\"DNI\"}";
                  case "/v1/tokens/describe" ->
                      "{\"token\":\"" + token.reveal() + "\",\"type\":\"DNI\",\"metadata\":{}}";
                  case "/v1/tokens/reveal" -> "{\"type\":\"DNI\",\"value\":\"00123456\"}";
                  case "/v1/tokens/delete" -> "";
                  default -> throw new AssertionError("Unexpected route");
                };
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Axiz-Token-Deleted", "true");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(
                path.endsWith("/delete") ? 204 : path.equals("/v1/tokens") ? 201 : 200,
                bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    var identity =
        new JwtAuthenticationToken(
            Jwt.withTokenValue("synthetic-caller-jwt")
                .header("alg", "RS256")
                .subject("customer-a")
                .build(),
            List.of());
    var configurations =
        AutoConfigurations.of(
            AxizHttpClientAutoConfiguration.class, AxizTokenizationRemoteAutoConfiguration.class);
    String[] settings = {
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true",
      "axiz.tokenization.mode=remote",
      "axiz.tokenization.remote.client=token-vault",
      "axiz.http-client.clients.token-vault.base-url=http://127.0.0.1:"
          + server.getAddress().getPort(),
      "axiz.http-client.clients.token-vault.retry=NONE",
      "axiz.http-client.clients.token-vault.max-attempts=1",
      "axiz.http-client.clients.token-vault.max-concurrent-calls=8",
      "axiz.http-client.clients.token-vault.auth.type=TOKEN_RELAY"
    };
    try {
      SecurityContextHolder.getContext().setAuthentication(identity);
      new ApplicationContextRunner()
          .withConfiguration(configurations)
          .withPropertyValues(settings)
          .run(
              ctx -> {
                var vault = ctx.getBean(Tokenizer.class);
                try (var value = SensitiveValue.of("00123456")) {
                  assertThat(
                          vault.tokenize(type, value, domain, UUID.randomUUID().toString()).token())
                      .isEqualTo(token);
                  assertThat(vault.lookup(type, value, domain, "customer-match")).contains(token);
                }
                assertThat(vault.describe(type, token, domain)).isPresent();
                try (var revealed =
                    vault.detokenize(type, token, domain, "approved-identity-check")) {
                  assertThat(revealed.reveal()).isEqualTo("00123456");
                }
                assertThat(vault.delete(type, token, domain)).isTrue();
                evidence.publishEntry("axiz.capability.tokenization", "servlet");
              });
      SecurityContextHolder.clearContext();
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(configurations)
          .withPropertyValues(settings)
          .run(
              ctx -> {
                assertThat(ctx).doesNotHaveBean(Tokenizer.class);
                var vault = ctx.getBean(ReactiveTokenizer.class);
                try (var value = SensitiveValue.of("00123456")) {
                  var result =
                      vault
                          .tokenize(type, value, domain, UUID.randomUUID().toString())
                          .contextWrite(ReactiveSecurityContextHolder.withAuthentication(identity))
                          .block(Duration.ofSeconds(10));
                  assertThat(result.token()).isEqualTo(token);
                  evidence.publishEntry("axiz.capability.tokenization", "reactive");
                }
              });
      assertThat(identities).hasSize(6).containsOnly("Bearer synthetic-caller-jwt");
    } finally {
      SecurityContextHolder.clearContext();
      server.stop(0);
    }
  }
}

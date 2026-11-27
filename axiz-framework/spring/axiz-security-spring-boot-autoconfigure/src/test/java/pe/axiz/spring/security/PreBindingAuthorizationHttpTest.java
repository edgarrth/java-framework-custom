package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import jakarta.servlet.Filter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.TrustedIssuer;
import reactor.core.publisher.Mono;

/**
 * Un invocante sin el scope requerido recibe 403 antes de que se lea el cuerpo; un 400 describiría
 * el contrato de un endpoint que el invocante no puede usar (OWASP API5).
 */
class PreBindingAuthorizationHttpTest {
  private static final String ISSUER = "https://prebinding-fixture.example";
  private static final KeyPair KEY = key();
  private static final String MALFORMED = "{\"amount\":";

  record Debit(String accountId, int amount) {}

  @RestController
  @PreAuthorize("hasAuthority('SCOPE_payments:process')")
  static class Debits {
    @PostMapping("/debits")
    String debit(@RequestBody Debit debit) {
      return "ok";
    }

    @PostMapping("/accounts/{id}/debits")
    @PreAuthorize("#id == 'own'")
    String debitOwn(@PathVariable String id, @RequestBody Debit debit) {
      return "ok";
    }
  }

  @RestController
  @PreAuthorize("hasAuthority('SCOPE_payments:process')")
  static class ReactiveDebits {
    @PostMapping("/debits")
    Mono<String> debit(@RequestBody Debit debit) {
      return Mono.just("ok");
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class Servlet {
    @Bean
    Debits debits() {
      return new Debits();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebFlux
  static class Reactive {
    @Bean
    ReactiveDebits debits() {
      return new ReactiveDebits();
    }
  }

  @Test
  void servletAuthorizesBeforeReadingTheBody() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(settings())
        .withUserConfiguration(Servlet.class)
        .withBean(TrustedIssuerDecoders.class, PreBindingAuthorizationHttpTest::decoders)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var client =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                      .build();
              client
                  .perform(
                      post("/debits")
                          .header("Authorization", "Bearer " + token(""))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(MALFORMED))
                  .andExpect(status().isForbidden());
              client
                  .perform(
                      post("/debits")
                          .header("Authorization", "Bearer " + token("payments:process"))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(MALFORMED))
                  .andExpect(status().isBadRequest());
              // una expresión que lee argumentos se decide después del binding
              client
                  .perform(
                      post("/accounts/other/debits")
                          .header("Authorization", "Bearer " + token("payments:process"))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"accountId\":\"a\",\"amount\":1}"))
                  .andExpect(status().isForbidden());
            });
  }

  @Test
  void webfluxAuthorizesBeforeReadingTheBody() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(settings())
        .withUserConfiguration(Reactive.class)
        .withBean(TrustedIssuerDecoders.class, PreBindingAuthorizationHttpTest::decoders)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var client = WebTestClient.bindToApplicationContext(context).build();
              client
                  .post()
                  .uri("/debits")
                  .header("Authorization", "Bearer " + token(""))
                  .contentType(MediaType.APPLICATION_JSON)
                  .bodyValue(MALFORMED)
                  .exchange()
                  .expectStatus()
                  .isForbidden();
              client
                  .post()
                  .uri("/debits")
                  .header("Authorization", "Bearer " + token("payments:process"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .bodyValue(MALFORMED)
                  .exchange()
                  .expectStatus()
                  .isBadRequest();
              client
                  .post()
                  .uri("/debits")
                  .header("Authorization", "Bearer " + token("payments:process"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .bodyValue("{\"accountId\":\"a\",\"amount\":1}")
                  .exchange()
                  .expectStatus()
                  .isOk();
            });
  }

  private static String[] settings() {
    return new String[] {
      "axiz.security.issuers[0].issuer=" + ISSUER,
      "axiz.security.issuers[0].audiences[0]=debits-api"
    };
  }

  private static KeyPair key() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static TrustedIssuerDecoders decoders() {
    var trusted = new TrustedIssuer(ISSUER, null, List.of("debits-api"));
    var mvc = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
    var flux = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
    var decoders =
        spy(
            new TrustedIssuerDecoders(
                new IssuerPolicy(List.of(trusted)), List.of("RS256"), Duration.ofSeconds(30)));
    mvc.setJwtValidator(decoders.validator(trusted));
    flux.setJwtValidator(decoders.validator(trusted));
    doReturn(mvc).when(decoders).servlet(ISSUER);
    doReturn(flux).when(decoders).reactive(ISSUER);
    return decoders;
  }

  private static String token(String scope) throws Exception {
    var claims = new LinkedHashMap<String, Object>();
    claims.put("iss", ISSUER);
    claims.put("sub", "channel-user");
    claims.put("azp", "caller");
    claims.put("aud", "debits-api");
    claims.put("scope", scope);
    claims.put("iat", Instant.now().getEpochSecond());
    claims.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
    var object = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
    object.sign(new RSASSASigner(KEY.getPrivate()));
    return object.serialize();
  }
}

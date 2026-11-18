package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.TrustedIssuer;
import pe.axiz.spring.tenancy.AxizTenancyAutoConfiguration;
import reactor.core.publisher.Mono;

/** Real signed bearer tokens: invalid tenant claims cannot be replaced by a trusted header. */
class TenantClaimHttpTest {
  private static final String ISSUER = "https://tenant-fixture.example";
  private static final KeyPair KEY = key();
  private static final List<Object> INVALID =
      Arrays.asList(null, "", " ", 7, List.of("bank-a"), Map.of("id", "bank-a"), "../outside");

  private static KeyPair key() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @RestController
  static class Endpoint {
    @GetMapping("/tenant-contract")
    @PreAuthorize("isAuthenticated()")
    String read() {
      return "ok";
    }
  }

  @RestController
  static class ReactiveEndpoint {
    @GetMapping("/tenant-contract")
    @PreAuthorize("isAuthenticated()")
    Mono<String> read() {
      return Mono.just("ok");
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class Servlet {
    @Bean
    Endpoint endpoint() {
      return new Endpoint();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebFlux
  static class Reactive {
    @Bean
    ReactiveEndpoint endpoint() {
      return new ReactiveEndpoint();
    }
  }

  private static TrustedIssuerDecoders decoders() {
    var trusted = new TrustedIssuer(ISSUER, null, List.of("tenant-api"));
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

  private static String[] settings(boolean optionalTenancy) {
    return new String[] {
      "axiz.security.issuers[0].issuer=" + ISSUER,
      "axiz.security.issuers[0].audiences[0]=tenant-api",
      "axiz.tenancy.enabled=" + optionalTenancy,
      "axiz.tenancy.required=false",
      "axiz.tenancy.trust-header=true"
    };
  }

  private static String token(boolean present, Object tenant) throws Exception {
    var claims = new LinkedHashMap<String, Object>();
    claims.put("iss", ISSUER);
    claims.put("sub", "user");
    claims.put("azp", "caller");
    claims.put("aud", "tenant-api");
    claims.put("iat", Instant.now().getEpochSecond());
    claims.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
    if (present) {
      claims.put("tenant", tenant);
      claims.put("tenant_id", "alternate-valid");
    }
    var object = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
    object.sign(new RSASSASigner(KEY.getPrivate()));
    return object.serialize();
  }

  @Test
  void servletRejectsInvalidClaimsWithTenancyDisabledAndOptional() {
    for (boolean optional : new boolean[] {false, true}) {
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizSecurityAutoConfiguration.class, AxizTenancyAutoConfiguration.class))
          .withPropertyValues(settings(optional))
          .withUserConfiguration(Servlet.class)
          .withBean(TrustedIssuerDecoders.class, TenantClaimHttpTest::decoders)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var filters = new ArrayList<Filter>();
                filters.add(context.getBean("springSecurityFilterChain", Filter.class));
                if (optional)
                  filters.add(
                      context
                          .getBean("axizTenantFilter", FilterRegistrationBean.class)
                          .getFilter());
                var client =
                    MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(filters.toArray(Filter[]::new))
                        .build();
                for (Object invalid : INVALID)
                  client
                      .perform(
                          get("/tenant-contract")
                              .header("Authorization", "Bearer " + token(true, invalid))
                              .header("X-Tenant-Id", "trusted-header"))
                      .andExpect(status().isUnauthorized());
                client
                    .perform(
                        get("/tenant-contract")
                            .header("Authorization", "Bearer " + token(false, null))
                            .header("X-Tenant-Id", "trusted-header"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"));
                client
                    .perform(
                        get("/tenant-contract")
                            .header("Authorization", "Bearer " + token(true, "bank-a"))
                            .header("X-Tenant-Id", "trusted-header"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"));
              });
    }
  }

  @Test
  void webfluxRejectsInvalidClaimsWithTenancyDisabledAndOptional() {
    for (boolean optional : new boolean[] {false, true}) {
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizSecurityAutoConfiguration.class, AxizTenancyAutoConfiguration.class))
          .withPropertyValues(settings(optional))
          .withUserConfiguration(Reactive.class)
          .withBean(TrustedIssuerDecoders.class, TenantClaimHttpTest::decoders)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var client = WebTestClient.bindToApplicationContext(context).build();
                for (Object invalid : INVALID)
                  client
                      .get()
                      .uri("/tenant-contract")
                      .header("Authorization", "Bearer " + token(true, invalid))
                      .header("X-Tenant-Id", "trusted-header")
                      .exchange()
                      .expectStatus()
                      .isUnauthorized();
                client
                    .get()
                    .uri("/tenant-contract")
                    .header("Authorization", "Bearer " + token(false, null))
                    .header("X-Tenant-Id", "trusted-header")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cross-Origin-Resource-Policy", "same-origin");
                client
                    .get()
                    .uri("/tenant-contract")
                    .header("Authorization", "Bearer " + token(true, "bank-a"))
                    .header("X-Tenant-Id", "trusted-header")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cross-Origin-Resource-Policy", "same-origin");
              });
    }
  }
}

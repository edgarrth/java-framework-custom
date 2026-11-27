package pe.axiz.spring.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.logging.HttpLogFormatter;
import pe.axiz.core.logging.LogOperation;
import pe.axiz.core.sensitive.ObjectMasker;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Logs de operaciones, el log de acceso HTTP en ambas pilas y los valores por defecto de logging.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingBehaviourTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizLoggingAutoConfiguration.class));

  @Test
  void payloadVetoPreventsBothRequestAndResponseCaptureEvenWhenBodyLoggingIsEnabled(
      CapturedOutput output) throws Exception {
    var filter =
        new HttpLoggingFilter(formatter(), http(true), List.of(path -> !path.startsWith("/vault")));
    var request = new MockHttpServletRequest("POST", "/app/vault/reveal");
    request.setContextPath("/app");
    request.setContentType("application/json");
    request.setContent("{\"value\":\"synthetic-source-canary\"}".getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (actualRequest, actualResponse) -> {
          assertThat(actualRequest).isSameAs(request);
          assertThat(actualResponse).isSameAs(response);
          assertThat(actualRequest.getInputStream().readAllBytes())
              .isEqualTo(request.getContentAsByteArray());
          actualResponse
              .getOutputStream()
              .write("synthetic-revealed-canary".getBytes(StandardCharsets.UTF_8));
        });
    assertThat(output.getAll())
        .doesNotContain(
            "synthetic-source-canary", "synthetic-revealed-canary", "request=", "response=");
    assertThat(response.getContentAsString()).isEqualTo("synthetic-revealed-canary");
  }

  /** Cada método público es una operación (anotación en el tipo). */
  @LogOperation(logArguments = false, logResult = true)
  public static class Statements {
    public String latest(String account) {
      return "statement-of-" + account;
    }

    public Mono<String> async() {
      return Mono.just("async-done");
    }

    public Flux<Integer> lines() {
      return Flux.just(1, 2);
    }

    public Mono<String> asyncRejected() {
      return Mono.error(new BusinessException(CoreErrors.CONFLICT, "duplicated"));
    }

    public String rejected() {
      throw new BusinessException(CoreErrors.CONFLICT, "duplicated");
    }
  }

  private static Statements proxy(Advisor advisor) {
    ProxyFactory pf = new ProxyFactory(new Statements());
    pf.setProxyTargetClass(true);
    pf.addAdvisor(advisor);
    return (Statements) pf.getProxy();
  }

  @Test
  void typeLevelOperationsLogResultsAndBusinessRejectionsAsWarnings(CapturedOutput output) {
    runner.run(
        ctx -> {
          Statements s = proxy(ctx.getBean("axizLogOperationAdvisor", Advisor.class));
          assertEquals("statement-of-123", s.latest("123"));
          assertEquals("async-done", s.async().block());
          assertEquals(List.of(1, 2), s.lines().collectList().block());
          assertThrows(BusinessException.class, () -> s.asyncRejected().block());
          assertThrows(BusinessException.class, s::rejected);
        });
    String log = output.getOut();
    assertThat(log)
        .contains("operation=Statements.latest phase=start")
        .doesNotContain("args=[123]")
        .contains("operation=Statements.latest phase=end outcome=success result=statement-of-123")
        .contains("operation=Statements.async phase=end outcome=success")
        .contains("operation=Statements.lines phase=end outcome=success")
        .contains("operation=Statements.asyncRejected phase=end outcome=rejected")
        .contains(
            "operation=Statements.rejected phase=end outcome=rejected error=BusinessException")
        .doesNotContain("outcome=error");
  }

  @Test
  void onlyAxizBusinessValidationAndSecurityErrorsAreExpected() {
    FailureKind kind = FailureKind.axiz();
    assertTrue(kind.isExpected(new BusinessException(CoreErrors.CONFLICT, "dup")));
    assertFalse(kind.isExpected(new AxizException(CoreErrors.UNEXPECTED, "boom")));
    assertFalse(kind.isExpected(new IllegalStateException("db down")));
    assertEquals("AXZ-CORE-0409", kind.code(new BusinessException(CoreErrors.CONFLICT, "dup")));
    assertNull(kind.code(new IllegalStateException("db down")));
  }

  private static HttpLogFormatter formatter() {
    return new HttpLogFormatter(
        List.of("Content-Type", "X-Correlation-Id", "Authorization"), 40, ObjectMasker.defaults());
  }

  private static AxizLoggingProperties.Http http(boolean bodies) {
    return http(bodies, 65_536);
  }

  private static AxizLoggingProperties.Http http(boolean bodies, int maxMaskedBodyLength) {
    return new AxizLoggingProperties.Http(
        true, List.of("Content-Type"), bodies, 40, List.of("/actuator/**"), maxMaskedBodyLength);
  }

  @Test
  void bodiesLargerThanWhatIsKeptAreLoggedBySizeOnly(CapturedOutput output) throws Exception {
    HttpLoggingFilter filter = new HttpLoggingFilter(formatter(), http(true, 32), List.of());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
    request.setAttribute(
        org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/login");
    request.setContentType("application/json");
    request.setContent(
        ("{\"user\":\"ana\",\"password\":\"SYNTHETIC-SECRET-" + "x".repeat(40) + "\"}")
            .getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          req.getInputStream().readAllBytes();
          res.setContentType("application/json");
          res.getWriter().write("{\"token\":\"SYNTHETIC-TOKEN-" + "y".repeat(40) + "\"}");
        });
    assertThat(response.getContentAsString()).startsWith("{\"token\":");
    assertThat(output.getOut())
        .contains("request=<application/json, more than 32 chars: too large to mask, not logged>")
        .contains("response=<application/json, 68 bytes: too large to mask, not logged>")
        .doesNotContain("SYNTHETIC-SECRET")
        .doesNotContain("SYNTHETIC-TOKEN");
  }

  @Test
  void theServletAccessLogHasOneLineWithoutSecretsOrQueryStrings(CapturedOutput output)
      throws Exception {
    HttpLoggingFilter filter = new HttpLoggingFilter(formatter(), http(false), List.of());
    assertEquals(Ordered.HIGHEST_PRECEDENCE + 30, filter.getOrder());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts");
    request.setAttribute(
        org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
        "/accounts");
    request.setQueryString("document=12345678");
    request.addHeader("X-Correlation-Id", "corr-12345678");
    request.addHeader("Authorization", "Bearer secret-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);
    filter.doFilter(request, response, new MockFilterChain());

    MockHttpServletRequest probe = new MockHttpServletRequest("GET", "/actuator/health");
    filter.doFilter(probe, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(output.getOut())
        .contains("http method=GET path=/accounts status=200")
        .contains("X-Correlation-Id=corr-12345678")
        .doesNotContain("secret-token")
        .doesNotContain("document=")
        .doesNotContain("path=/actuator/health");
  }

  @Test
  void bodiesAreMaskedTruncatedAndStillReachTheClient(CapturedOutput output) throws Exception {
    HttpLoggingFilter filter = new HttpLoggingFilter(formatter(), http(true), List.of());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
    request.setAttribute(
        org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/login");
    request.setContentType("application/json");
    request.setContent(
        "{\"user\":\"ana\",\"password\":\"s3cret\"}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          req.getInputStream().readAllBytes();
          res.setContentType("text/plain");
          res.getWriter().write("welcome");
        });
    assertEquals("welcome", response.getContentAsString(), "the body still reaches the client");
    assertThat(output.getOut())
        .contains("http method=POST path=/login")
        .contains("response=<text/plain")
        .doesNotContain("s3cret");
  }

  @Test
  void theReactiveAccessLogSkipsExcludedPaths(CapturedOutput output) {
    HttpLoggingWebFilter filter = new HttpLoggingWebFilter(formatter(), http(false));
    assertEquals(Ordered.HIGHEST_PRECEDENCE + 30, filter.getOrder());
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/payments").header("X-Correlation-Id", "corr-87654321"));
    filter
        .filter(
            exchange,
            ex -> {
              ex.getAttributes()
                  .put(
                      org.springframework.web.reactive.HandlerMapping
                          .BEST_MATCHING_PATTERN_ATTRIBUTE,
                      new org.springframework.web.util.pattern.PathPatternParser()
                          .parse("/payments"));
              return Mono.empty();
            })
        .block();
    MockServerWebExchange probe =
        MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
    filter.filter(probe, ex -> Mono.empty()).block();
    assertThat(output.getOut())
        .contains("http method=GET path=/payments")
        .contains("X-Correlation-Id=corr-87654321")
        .doesNotContain("path=/actuator/health");
  }

  @Test
  void jsonCanBeForcedOrAvoidedAndDefaultsAreTheLowestPrecedence() {
    AxizLoggingEnvironmentPostProcessor epp = new AxizLoggingEnvironmentPostProcessor();
    MockEnvironment forced = new MockEnvironment().withProperty("axiz.logging.json", "true");
    epp.postProcessEnvironment(forced, new SpringApplication());
    assertEquals("logstash", forced.getProperty("logging.structured.format.console"));
    assertEquals("application", forced.getProperty("logging.structured.json.add.service"));

    MockEnvironment localPod =
        new MockEnvironment().withProperty("KUBERNETES_SERVICE_HOST", "10.0.0.1");
    localPod.setActiveProfiles("local");
    epp.postProcessEnvironment(localPod, new SpringApplication());
    assertNull(localPod.getProperty("logging.structured.format.console"));
    assertEquals("INFO", localPod.getProperty("logging.level.axiz.http"));

    MockEnvironment off =
        new MockEnvironment()
            .withProperty("KUBERNETES_SERVICE_HOST", "10.0.0.1")
            .withProperty("axiz.logging.json", "false");
    epp.postProcessEnvironment(off, new SpringApplication());
    assertNull(off.getProperty("logging.structured.format.console"));
    assertEquals(Ordered.LOWEST_PRECEDENCE, epp.getOrder());
  }

  @Test
  void eachStackGetsItsAccessLogAndBothCanBeSwitchedOff() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizLoggingAutoConfiguration.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasSingleBean(HttpLoggingFilter.class)
                    .doesNotHaveBean(HttpLoggingWebFilter.class));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizLoggingAutoConfiguration.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasSingleBean(HttpLoggingWebFilter.class)
                    .doesNotHaveBean(HttpLoggingFilter.class));
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizLoggingAutoConfiguration.class))
        .withPropertyValues("axiz.logging.http.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(HttpLoggingFilter.class));
    runner
        .withPropertyValues("axiz.logging.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(HttpLogFormatter.class));
  }
}

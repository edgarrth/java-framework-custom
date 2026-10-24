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

/** Operation logs, the HTTP access log on both stacks, and the logging defaults. */
@ExtendWith(OutputCaptureExtension.class)
class LoggingBehaviourTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizLoggingAutoConfiguration.class));

  /** Every public method is an operation (annotation on the type). */
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
    return new AxizLoggingProperties.Http(
        true, List.of("Content-Type"), bodies, 40, List.of("/actuator/**"));
  }

  @Test
  void theServletAccessLogHasOneLineWithoutSecretsOrQueryStrings(CapturedOutput output)
      throws Exception {
    HttpLoggingFilter filter = new HttpLoggingFilter(formatter(), http(false));
    assertEquals(Ordered.HIGHEST_PRECEDENCE + 30, filter.getOrder());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts");
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
    HttpLoggingFilter filter = new HttpLoggingFilter(formatter(), http(true));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
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
    filter.filter(exchange, ex -> Mono.empty()).block();
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

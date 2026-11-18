package pe.axiz.spring.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.RequestDispatcher;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.RateLimitedException;
import reactor.core.publisher.Mono;

/** Every failure reaches the client as an RFC 9457 problem with a stable code and no internals. */
class ProblemResponsesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class));

  @Test
  void confirmedRateLimitCarriesABoundedDelayInServletReactiveAndErrorDispatch() {
    runner.run(
        ctx -> {
          var responder = ctx.getBean(ProblemResponder.class);
          var failure =
              new CompletionException(
                  new RateLimitedException(CoreErrors.TOO_MANY_REQUESTS, Duration.ofMillis(1501)));
          var request = new MockHttpServletRequest("POST", "/token");
          var servlet = new ProblemControllerAdvice(responder).handle(failure, request);
          assertEquals(429, servlet.getStatusCode().value());
          assertEquals("2", servlet.getHeaders().getFirst("Retry-After"));
          request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
          var dispatched = new ProblemErrorController(responder).error(request);
          assertEquals("2", dispatched.getHeaders().getFirst("Retry-After"));
          var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/token"));
          new ProblemWebExceptionHandler(responder)
              .handle(
                  exchange,
                  new RateLimitedException(
                      CoreErrors.TOO_MANY_REQUESTS, Duration.ofSeconds(Long.MAX_VALUE)))
              .block();
          assertEquals(429, exchange.getResponse().getStatusCode().value());
          assertEquals("86400", exchange.getResponse().getHeaders().getFirst("Retry-After"));
          var uncertain =
              new ProblemControllerAdvice(responder)
                  .handle(new BusinessException(CoreErrors.OUTCOME_UNKNOWN, "synthetic"), request);
          assertEquals(504, uncertain.getStatusCode().value());
          assertFalse(uncertain.getHeaders().containsHeader("Retry-After"));
        });
  }

  @RestController
  static class Payments {

    @GetMapping("/bound")
    String bound() throws BindException {
      BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "payment");
      result.reject("AmountPositive", "amount must be positive");
      throw new BindException(result);
    }

    @GetMapping("/header")
    String header(@RequestHeader("X-Channel") String channel) {
      return channel;
    }

    @PostMapping(path = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    String body(@RequestBody Map<String, Object> body) {
      return "ok";
    }

    @GetMapping("/denied")
    String denied() {
      throw new AccessDeniedException("role ADMIN missing for user 42");
    }

    @GetMapping("/login")
    String login() {
      throw new BadCredentialsException("password mismatch for user 42");
    }

    @GetMapping("/stale")
    String stale() {
      throw new OptimisticLockingFailureException("row version 7 changed");
    }

    @GetMapping("/constraints")
    String constraints() {
      throw new ConstraintViolationException("amount: must be positive", Set.of());
    }

    @GetMapping("/gone")
    String gone() {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account 123 not in table x");
    }

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("jdbc:postgresql://db.internal password=secret");
    }
  }

  private static MockMvc mvc(ProblemResponder responder) {
    return MockMvcBuilders.standaloneSetup(new Payments())
        .setControllerAdvice(new ProblemControllerAdvice(responder))
        .build();
  }

  private static MvcResult call(MockMvc mvc, String path) throws Exception {
    return mvc.perform(get(path)).andReturn();
  }

  private static void assertProblem(MvcResult result, int status, String code) throws Exception {
    assertEquals(status, result.getResponse().getStatus());
    assertEquals(Problem.MEDIA_TYPE, result.getResponse().getContentType());
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).contains("\"code\":\"" + code + "\"");
    assertThat(body).doesNotContain("user 42", "row version", "table x", "password");
  }

  @Test
  void springMvcFailuresBecomeCataloguedProblems() {
    runner.run(
        ctx -> {
          MockMvc mvc = mvc(ctx.getBean(ProblemResponder.class));
          MvcResult bound = call(mvc, "/bound");
          assertProblem(bound, 400, "AXZ-CORE-0001");
          assertThat(bound.getResponse().getContentAsString()).contains("AmountPositive");
          assertProblem(call(mvc, "/header"), 400, "AXZ-CORE-0002");
          assertProblem(
              mvc.perform(
                      post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":"))
                  .andReturn(),
              400,
              "AXZ-CORE-0003");
          assertProblem(call(mvc, "/constraints"), 400, "AXZ-CORE-0001");
          assertProblem(call(mvc, "/denied"), 403, "AXZ-SEC-0403");
          assertProblem(call(mvc, "/login"), 401, "AXZ-SEC-0401");
          assertProblem(call(mvc, "/stale"), 409, "AXZ-CORE-0409");
          assertProblem(call(mvc, "/gone"), 404, "AXZ-CORE-0404");
          assertEquals(500, call(mvc, "/boom").getResponse().getStatus());
          assertFalse(call(mvc, "/boom").getResponse().getContentAsString().contains("secret"));
        });
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void deniedAccessIsLoggedAsAWarningAndConflictsAsInformation(CapturedOutput output) {
    runner.run(
        ctx -> {
          ProblemResponder responder = ctx.getBean(ProblemResponder.class);
          responder.problem(new AccessDeniedException("x"), Locale.ENGLISH, "/admin", "corr-0001");
          responder.problem(
              new OptimisticLockingFailureException("x"), Locale.ENGLISH, "/accounts", "corr-0002");
        });
    assertThat(output.getOut())
        .contains("WARN")
        .contains("code=AXZ-SEC-0403 correlationId=corr-0001")
        .contains("INFO")
        .contains("code=AXZ-CORE-0409 correlationId=corr-0002")
        .doesNotContain("ERROR");
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void logsContainNoExceptionMessagesPathsOrThrowableChains(CapturedOutput output) {
    String canary = "4111111111111111";
    runner.run(
        ctx -> {
          var responder = ctx.getBean(ProblemResponder.class);
          var first = new IllegalStateException("password=secret-canary-123 " + canary);
          var second = new IllegalArgumentException("token=secret-nested-456", first);
          first.initCause(
              second); // cyclic cause graphs must not trigger traversal or leak messages
          responder.problem(first, Locale.ENGLISH, "/cards/" + canary, "safe-correlation");
          responder.problem(
              new AccessDeniedException("password=secret-denied-789"),
              Locale.ENGLISH,
              "/credentials/secret-path-987",
              "safe-denial");
        });
    assertThat(output.getOut())
        .contains("safe-correlation", "java.lang.IllegalStateException", "safe-denial")
        .doesNotContain(
            canary,
            "secret-canary-123",
            "secret-nested-456",
            "secret-denied-789",
            "secret-path-987");
  }

  @Test
  void containerErrorsAreRenderedByTheErrorController() {
    runner.run(
        ctx -> {
          ProblemErrorController controller =
              new ProblemErrorController(ctx.getBean(ProblemResponder.class));
          MockHttpServletRequest notFound = new MockHttpServletRequest("GET", "/error");
          notFound.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
          notFound.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/missing");
          ResponseEntity<String> missing = controller.error(notFound);
          assertEquals(404, missing.getStatusCode().value());
          assertThat(missing.getBody()).contains("AXZ-CORE-0404").contains("/missing");

          MockHttpServletRequest filterFailure = new MockHttpServletRequest("GET", "/error");
          filterFailure.setAttribute(
              RequestDispatcher.ERROR_EXCEPTION, new AccessDeniedException("filter said no"));
          assertEquals(403, controller.error(filterFailure).getStatusCode().value());

          MockHttpServletRequest unknown = new MockHttpServletRequest("GET", "/error");
          assertEquals(500, controller.error(unknown).getStatusCode().value());
        });
  }

  @Test
  void webFluxFailuresBecomeProblemsUnlessTheResponseIsCommitted() {
    runner.run(
        ctx -> {
          ProblemWebExceptionHandler handler =
              new ProblemWebExceptionHandler(ctx.getBean(ProblemResponder.class));
          MockServerWebExchange exchange =
              MockServerWebExchange.from(
                  MockServerHttpRequest.get("/accounts/1").header("X-Correlation-Id", "corr-0007"));
          handler
              .handle(exchange, new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED))
              .block();
          assertEquals(405, exchange.getResponse().getStatusCode().value());
          assertEquals(
              Problem.MEDIA_TYPE, exchange.getResponse().getHeaders().getContentType().toString());
          assertThat(exchange.getResponse().getBodyAsString().block())
              .contains("AXZ-CORE-0405")
              .contains("corr-0007");

          MockServerWebExchange committed =
              MockServerWebExchange.from(MockServerHttpRequest.get("/stream"));
          committed.getResponse().setComplete().block();
          IllegalStateException late = new IllegalStateException("late");
          Mono<Void> result = handler.handle(committed, late);
          assertSame(late, assertThrows(IllegalStateException.class, result::block));
        });
  }

  @Test
  void eachStackGetsItsHandlerAndAServiceMayBringItsOwn() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasSingleBean(ProblemControllerAdvice.class)
                    .hasSingleBean(ProblemErrorController.class)
                    .doesNotHaveBean(ProblemWebExceptionHandler.class));
    ErrorController own = new ErrorController() {};
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
        .withBean(ErrorController.class, () -> own)
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ProblemErrorController.class));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasSingleBean(ProblemWebExceptionHandler.class)
                    .doesNotHaveBean(ProblemControllerAdvice.class));
    runner
        .withPropertyValues("axiz.errors.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ProblemResponder.class));
  }
}

package pe.axiz.spring.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.codec.DecodingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ResponseStatusException;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.errors.Problem;

/**
 * Cada fallo de Spring se convierte en el problema catalogado, y cada tipo se registra en su nivel.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExceptionMappingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class));

  /** Handler cuyos parámetros mencionan las excepciones. */
  static final class Handler {
    @SuppressWarnings("unused")
    void transfer(String account, String amount) {}
  }

  private static MethodParameter parameter(int index) throws NoSuchMethodException {
    Method m = Handler.class.getDeclaredMethod("transfer", String.class, String.class);
    return new MethodParameter(m, index);
  }

  private static Problem problem(ProblemResponder responder, Throwable failure) {
    return responder.problem(failure, Locale.ENGLISH, "/transfers", "corr-12345678");
  }

  private static String json(ProblemResponder responder, Throwable failure) {
    return responder.json(problem(responder, failure));
  }

  @Test
  void bindingErrorsListEveryFieldAndObjectViolation() {
    runner.run(
        ctx -> {
          ProblemResponder responder = ctx.getBean(ProblemResponder.class);
          BindException bind = new BindException(new Object(), "transfer");
          bind.addError(new FieldError("transfer", "amount", null, false, null, null, null));
          bind.addError(
              new ObjectError(
                  "transfer", new String[] {"SameCurrency"}, null, "currencies differ"));
          bind.addError(new ObjectError("transfer", null, null, null));
          Problem bound = problem(responder, bind);
          assertEquals(400, bound.status());
          assertEquals("AXZ-CORE-0001", bound.code());
          assertThat(responder.json(bound))
              .contains("amount", "SameCurrency", "currencies differ", "invalid value");

          BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "body");
          result.addError(
              new FieldError(
                  "body", "account", "x", false, new String[] {"Pattern"}, null, "bad account"));
          Problem reactive = problem(responder, new WebExchangeBindException(parameter(0), result));
          assertEquals("AXZ-CORE-0001", reactive.code());
          assertThat(responder.json(reactive)).contains("account", "bad account");
        });
  }

  @Test
  void methodValidationNamesTheParameterAndTheLastCode() {
    runner.run(
        ctx -> {
          ProblemResponder responder = ctx.getBean(ProblemResponder.class);
          ParameterValidationResult amount =
              new ParameterValidationResult(
                  parameter(1),
                  "-5",
                  List.of(
                      new DefaultMessageSourceResolvable(
                          new String[] {"Positive.amount", "Positive"}, "must be positive"),
                      new DefaultMessageSourceResolvable(null, null, null)),
                  null,
                  null,
                  null,
                  (error, type) -> error);
          MethodValidationResult result =
              MethodValidationResult.create(
                  new Handler(),
                  Handler.class.getDeclaredMethod("transfer", String.class, String.class),
                  List.of(amount));
          Problem p = problem(responder, new HandlerMethodValidationException(result));
          assertEquals(400, p.status());
          assertEquals("AXZ-CORE-0001", p.code());
          assertThat(responder.json(p)).contains("Positive", "must be positive", "Invalid");
        });
  }

  @Test
  void headersBodiesSecurityAndDataFailuresHaveTheirCodes() {
    runner.run(
        ctx -> {
          ProblemResponder responder = ctx.getBean(ProblemResponder.class);
          Problem servletHeader =
              problem(responder, new MissingRequestHeaderException("X-Channel", parameter(0)));
          assertEquals("AXZ-CORE-0002", servletHeader.code());
          assertEquals(400, servletHeader.status());

          Problem reactiveHeader =
              problem(
                  responder,
                  new MissingRequestValueException(
                      "X-Channel", String.class, "header", parameter(0)));
          assertEquals("AXZ-CORE-0002", reactiveHeader.code());

          Problem query =
              problem(
                  responder,
                  new MissingRequestValueException(
                      "page", String.class, "query parameter", parameter(0)));
          assertEquals(400, query.status());
          assertThat(query.code()).isNotEqualTo("AXZ-CORE-0002");

          assertEquals(
              "AXZ-CORE-0003", problem(responder, new DecodingException("bad json")).code());
          assertEquals(
              "AXZ-SEC-0401", problem(responder, new BadCredentialsException("bad")).code());
          assertEquals(
              "AXZ-CORE-0409",
              problem(responder, new DataIntegrityViolationException("uk_transfer")).code());
          assertEquals(
              405,
              problem(responder, new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED))
                  .status());
          assertThat(json(responder, new DataIntegrityViolationException("uk_transfer")))
              .doesNotContain("uk_transfer");
        });
  }

  @Test
  void eachKindOfFailureIsLoggedAtItsLevel(CapturedOutput output) {
    runner.run(
        ctx -> {
          ProblemResponder responder = ctx.getBean(ProblemResponder.class);
          // la dependencia respondió con un error: no hay causa de transporte que informar
          Problem rejected =
              problem(
                  responder,
                  ExternalServiceException.from(
                      new ExternalErrorDetail("core-banking", 503, null, null, "")));
          assertThat(rejected.status()).isGreaterThanOrEqualTo(500);
          // un fallo de transporte sin mensaje se nombra por su tipo
          problem(
              responder,
              ExternalServiceException.from(
                  new ExternalErrorDetail("ledger", -1, "ConnectException", null, ""),
                  new IllegalStateException(new ConnectException())));
          // los diagnósticos conservan la clasificación, nunca el texto del proveedor ni las causas
          problem(
              responder,
              new AxizException(
                  CoreErrors.UNEXPECTED, "ledger posting failed", new IllegalStateException("db")));
          // un error del cliente solo es visible en nivel debug
          problem(responder, new ResponseStatusException(HttpStatus.NOT_FOUND));

          MDC.put("correlationId", "corr-from-mdc");
          try {
            Problem fromMdc =
                responder.problem(
                    new ResponseStatusException(HttpStatus.NOT_FOUND), Locale.ENGLISH, null, null);
            assertThat(responder.json(fromMdc)).contains("corr-from-mdc");
          } finally {
            MDC.remove("correlationId");
          }
        });
    assertThat(output.getOut())
        .contains(
            "WARN", "status=503", "exceptionType=pe.axiz.core.errors.ExternalServiceException")
        .contains("ERROR", "status=500", "exceptionType=pe.axiz.core.errors.AxizException")
        .contains("correlationId=corr-12345678")
        // la cadena de tipos y los frames de origen ubican la causa; los mensajes nunca aparecen
        .contains("| caused by java.net.ConnectException at ")
        .doesNotContain(
            "path=/transfers",
            "Call to core-banking",
            "cause=",
            "ledger posting failed",
            "IllegalStateException: db",
            "\"db\"");
  }

  @Test
  void theErrorControllerRendersWhatTheContainerLeft() {
    runner.run(
        ctx -> {
          ProblemErrorController controller =
              new ProblemErrorController(ctx.getBean(ProblemResponder.class));
          ResponseEntity<String> unknown = controller.error(new MockHttpServletRequest());
          assertEquals(500, unknown.getStatusCode().value());
          assertEquals(ProblemResponder.MEDIA_TYPE, unknown.getHeaders().getFirst("Content-Type"));

          MockHttpServletRequest notFound = new MockHttpServletRequest();
          notFound.setAttribute("jakarta.servlet.error.status_code", 404);
          notFound.setAttribute("jakarta.servlet.error.request_uri", "/missing");
          ResponseEntity<String> missing = controller.error(notFound);
          assertEquals(404, missing.getStatusCode().value());
          assertThat(missing.getBody()).contains("/missing");

          MockHttpServletRequest odd = new MockHttpServletRequest();
          odd.setAttribute("jakarta.servlet.error.status_code", "not-a-number");
          assertEquals(500, controller.error(odd).getStatusCode().value());

          MockHttpServletRequest failedPost = new MockHttpServletRequest("POST", "/error");
          failedPost.setAttribute("jakarta.servlet.error.status_code", 409);
          assertEquals(409, controller.unsafeMethodError(failedPost).getStatusCode().value());
          MockHttpServletRequest failedGet = new MockHttpServletRequest("GET", "/error");
          failedGet.setAttribute("jakarta.servlet.error.status_code", 404);
          assertEquals(404, controller.safeMethodError(failedGet).getStatusCode().value());
        });
  }

  @Test
  void theErrorPageAnswersEveryMethodOnceWithSafeAndUnsafeMethodsApart() throws Exception {
    Set<RequestMethod> safe = methodsOf("safeMethodError");
    Set<RequestMethod> unsafe = methodsOf("unsafeMethodError");
    assertThat(safe)
        .containsExactlyInAnyOrder(
            RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE);
    assertThat(unsafe).doesNotContainAnyElementsOf(safe);
    Set<RequestMethod> all = EnumSet.copyOf(safe);
    all.addAll(unsafe);
    assertThat(all).containsExactlyInAnyOrder(RequestMethod.values());
  }

  private static Set<RequestMethod> methodsOf(String name) throws NoSuchMethodException {
    RequestMapping mapping =
        ProblemErrorController.class
            .getDeclaredMethod(name, HttpServletRequest.class)
            .getAnnotation(RequestMapping.class);
    assertThat(mapping.path()).containsExactly("${server.error.path:${error.path:/error}}");
    return EnumSet.copyOf(List.of(mapping.method()));
  }
}

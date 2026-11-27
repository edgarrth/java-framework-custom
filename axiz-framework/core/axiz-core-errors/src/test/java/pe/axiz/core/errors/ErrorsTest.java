package pe.axiz.core.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void retryAfterIsNumericBoundedAndAvailableOnlyForRateLimits() {
    assertEquals(
        1,
        new RateLimitedException(CoreErrors.TOO_MANY_REQUESTS, Duration.ZERO).retryAfterSeconds());
    assertEquals(
        2,
        new RateLimitedException(CoreErrors.TOO_MANY_REQUESTS, Duration.ofMillis(1001))
            .retryAfterSeconds());
    assertEquals(
        86400,
        new RateLimitedException(CoreErrors.TOO_MANY_REQUESTS, Duration.ofDays(90))
            .retryAfterSeconds());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RateLimitedException(CoreErrors.UNAVAILABLE, Duration.ofSeconds(1)));
  }

  @Test
  void errorCodeFormat() {
    assertEquals("AXZ", ErrorCode.of("AXZ-HTTP-0001").prefix());
    assertThrows(IllegalArgumentException.class, () -> ErrorCode.of("axz-http-1"));
    assertThrows(IllegalArgumentException.class, () -> ErrorCode.of("AXZ-HTTP-01"));
    assertThrows(IllegalArgumentException.class, () -> new ErrorCode(null));
  }

  @Test
  void everyCategoryHasStatusAndDefaultDefinition() {
    for (ErrorCategory c : ErrorCategory.values()) {
      assertTrue(c.httpStatus() >= 400 && c.httpStatus() < 600);
      assertEquals(
          c == ErrorCategory.OUTCOME_UNKNOWN ? ErrorCategory.GATEWAY_TIMEOUT : c,
          ErrorCategory.fromHttpStatus(c.httpStatus()));
      CoreErrors.forCategory(c);
    }
    assertEquals(ErrorCategory.UNPROCESSABLE, ErrorCategory.fromHttpStatus(418));
    assertEquals(ErrorCategory.BAD_GATEWAY, ErrorCategory.fromHttpStatus(599));
    assertEquals(ErrorCategory.GATEWAY_TIMEOUT, ErrorCategory.fromHttpStatus(408));
    assertEquals(ErrorCategory.INTERNAL, ErrorCategory.fromHttpStatus(302));
    assertTrue(ErrorCategory.SERVICE_UNAVAILABLE.retryable());
    assertFalse(ErrorCategory.CONFLICT.retryable());
    assertFalse(ErrorCategory.OUTCOME_UNKNOWN.retryable());
    assertEquals(ErrorCategory.OUTCOME_UNKNOWN, CoreErrors.OUTCOME_UNKNOWN.category());
  }

  @Test
  void externalStatusMapping() {
    assertEquals(ErrorCategory.BAD_GATEWAY, ErrorCategory.forExternalStatus(401));
    assertEquals(ErrorCategory.NOT_FOUND, ErrorCategory.forExternalStatus(404));
    assertEquals(ErrorCategory.CONFLICT, ErrorCategory.forExternalStatus(409));
    assertEquals(ErrorCategory.GATEWAY_TIMEOUT, ErrorCategory.forExternalStatus(504));
    assertEquals(ErrorCategory.SERVICE_UNAVAILABLE, ErrorCategory.forExternalStatus(429));
    assertEquals(ErrorCategory.UNPROCESSABLE, ErrorCategory.forExternalStatus(400));
    assertEquals(ErrorCategory.BAD_GATEWAY, ErrorCategory.forExternalStatus(500));
  }

  @Test
  void catalogLoadsFromPropertiesAndOverrides() {
    ErrorCatalog catalog =
        ErrorCatalog.builder()
            .addProperties(
                Map.of(
                    "PAY-ORDER-0001.category", "unprocessable",
                    "PAY-ORDER-0001.title", "Daily limit exceeded",
                    "AXZ-CORE-0404.category", "NOT_FOUND",
                    "AXZ-CORE-0404.title", "No existe el recurso",
                    "AXZ-CORE-0404.type", "business"))
            .build();
    ErrorDefinition def = catalog.require("PAY-ORDER-0001");
    assertEquals(ErrorCategory.UNPROCESSABLE, def.category());
    assertEquals(ErrorType.BUSINESS, def.type());
    assertEquals("error.PAY-ORDER-0001", def.messageKey());
    assertEquals("No existe el recurso", catalog.require("AXZ-CORE-0404").title());
    assertEquals(CoreErrors.all().size() + 1, catalog.size());
    assertThrows(IllegalArgumentException.class, () -> catalog.require("PAY-ORDER-9999"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ErrorCatalog.builder().addProperties(Map.of("PAY-X-0001.title", "no category")));
  }

  @Test
  void problemFromBusinessExceptionUsesLocalizedDetail() {
    ErrorDefinition limit =
        ErrorDefinition.of("PAY-ORDER-0001", ErrorCategory.UNPROCESSABLE, "Limit");
    ErrorCatalog catalog = ErrorCatalog.builder().add(limit).build();
    ErrorMessageResolver resolver =
        (key, locale, params) ->
            key.equals("error.PAY-ORDER-0001") && locale.getLanguage().equals("es")
                ? Optional.of("Supera el límite de " + params.get("limit"))
                : Optional.empty();
    ProblemFactory factory =
        new ProblemFactory(
            catalog,
            resolver,
            List.of(),
            new ProblemFactory.Settings("https://errors.example/", false),
            CLOCK);
    Problem p =
        factory.create(
            new BusinessException(limit, "limit exceeded for account", Map.of("limit", 5000)),
            Locale.forLanguageTag("es-PE"),
            "/v1/orders",
            "c-1");
    assertEquals(422, p.status());
    assertEquals("Supera el límite de 5000", p.detail());
    assertEquals("https://errors.example/pay-order-0001", p.type());
    assertEquals("PAY-ORDER-0001", p.code());
    assertEquals("c-1", p.correlationId());
    assertEquals(Instant.parse("2026-05-01T12:00:00Z"), p.timestamp());
    Problem en = factory.create(new BusinessException(limit, "x"), Locale.ENGLISH, null, null);
    assertEquals("Limit", en.detail());
  }

  @Test
  void unknownExceptionNeverLeaksItsMessage() {
    ProblemFactory factory = defaultFactory(List.of());
    Problem p =
        factory.create(
            new IllegalStateException("SELECT * FROM accounts failed: password=x"),
            Locale.ROOT,
            "/x",
            null);
    assertEquals(500, p.status());
    assertEquals("AXZ-CORE-0500", p.code());
    assertEquals("Unexpected error", p.detail());
    assertEquals("about:blank", p.type());
    assertFalse(p.detail().contains("password"));
  }

  @Test
  void mappersTranslateThirdPartyExceptionsInCauseChain() {
    ExceptionMapper mapper =
        ExceptionMapper.byClassName(IllegalArgumentException.class.getName(), CoreErrors.CONFLICT);
    ProblemFactory factory = defaultFactory(List.of(mapper));
    Problem p =
        factory.create(
            new RuntimeException("wrapper", new IllegalArgumentException("inner")),
            Locale.ROOT,
            null,
            null);
    assertEquals(409, p.status());
    // las subclases coinciden por jerarquía de clases
    Problem p2 = factory.create(new NumberFormatException("n"), Locale.ROOT, null, null);
    assertEquals(409, p2.status());
  }

  @Test
  void validationProblemCarriesViolations() {
    ProblemFactory factory = defaultFactory(List.of());
    Problem p =
        factory.create(
            new ValidationException(
                List.of(FieldViolation.of("amount", "Positive", "must be > 0"))),
            Locale.ROOT,
            null,
            null);
    assertEquals(400, p.status());
    assertEquals(1, p.violations().size());
    assertEquals("amount", p.violations().get(0).field());
  }

  @ParameterizedTest
  @CsvSource({"503,503", "504,504", "500,502", "404,404", "401,502", "-1,503"})
  void externalErrorsKeepEmptyBodyAndCategory(int remoteStatus, int expectedStatus) {
    ExternalErrorDetail detail =
        new ExternalErrorDetail("core-ledger", remoteStatus, null, null, "");
    ExternalServiceException ex = ExternalServiceException.from(detail);
    assertTrue(ex.detail().emptyBody());
    assertEquals(expectedStatus, ex.category().httpStatus());
    Problem p = defaultFactory(List.of()).create(ex, Locale.ROOT, null, null);
    assertEquals(expectedStatus, p.status());
    assertNull(p.external());
  }

  @Test
  void transportFailuresAreTimeoutsOrUnavailableAndSaySo() {
    ExternalErrorDetail noAnswer =
        new ExternalErrorDetail("core-banking", -1, "SocketTimeoutException", null, "");
    ExternalServiceException timeout =
        ExternalServiceException.from(
            noAnswer, new IOException("I/O error", new SocketTimeoutException("Read")));
    assertEquals(504, timeout.category().httpStatus());
    assertEquals("AXZ-EXT-0504", timeout.code().value());
    assertEquals(
        "Call to core-banking got no response (SocketTimeoutException)", timeout.getMessage());
    // un timeout de una librería cliente que este módulo no conoce, reconocido por el nombre de su
    // tipo
    assertEquals(
        504,
        ExternalServiceException.from(noAnswer, new ReadTimeoutException())
            .category()
            .httpStatus());
    ExternalServiceException refused =
        ExternalServiceException.from(
            new ExternalErrorDetail("core-banking", -1, "ConnectException", null, ""),
            new ConnectException("Connection refused"));
    assertEquals(503, refused.category().httpStatus());
    assertEquals("Call to core-banking got no response (ConnectException)", refused.getMessage());
    assertEquals(
        "Call to core-banking failed with status 500",
        ExternalServiceException.from(new ExternalErrorDetail("core-banking", 500, null, null, ""))
            .getMessage());
  }

  /**
   * Representa un tipo de timeout de una librería cliente (por ejemplo, el de Netty), sin relación
   * con los del JDK.
   */
  private static final class ReadTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  @Test
  void externalDetailIsExposedOnlyWhenEnabled() {
    ProblemFactory factory =
        new ProblemFactory(
            ErrorCatalog.defaults(),
            ErrorMessageResolver.none(),
            List.of(),
            new ProblemFactory.Settings("", true),
            CLOCK);
    ExternalErrorDetail detail =
        new ExternalErrorDetail("card-network", 400, "R05", "Do not honor", "x".repeat(5000));
    assertEquals(ExternalErrorDetail.MAX_BODY, detail.body().length());
    Problem p = factory.create(ExternalServiceException.from(detail), Locale.ROOT, null, null);
    assertEquals(Map.of("system", "card-network", "status", 400, "code", "R05"), p.external());
  }

  @Test
  void frameworkDetectedErrors() {
    Problem p =
        defaultFactory(List.of())
            .of(CoreErrors.MISSING_HEADER, Locale.ROOT, Map.of("header", "X-Channel"), "/v1", "c");
    assertEquals(400, p.status());
    assertEquals("AXZ-CORE-0002", p.code());
    NotFoundException nf = new NotFoundException("Order", 42);
    assertEquals("42", nf.parameters().get("id"));
    assertEquals(401, AccessException.unauthenticated("no token").category().httpStatus());
    assertEquals(403, AccessException.forbidden("scope").category().httpStatus());
  }

  private static ProblemFactory defaultFactory(List<ExceptionMapper> mappers) {
    return new ProblemFactory(
        ErrorCatalog.defaults(),
        ErrorMessageResolver.none(),
        mappers,
        ProblemFactory.Settings.defaults(),
        CLOCK);
  }

  @Test
  void aBrokenMapperNeverStopsTheErrorResponse() {
    ExceptionMapper broken =
        failure -> {
          throw new NoClassDefFoundError("jakarta/servlet/ServletException");
        };
    ExceptionMapper timeouts =
        failure ->
            failure instanceof TimeoutException
                ? Optional.<AxizException>of(new BusinessException(CoreErrors.CONFLICT, "late"))
                : Optional.empty();
    IllegalStateException original = new IllegalStateException("boom");
    Problem p = defaultFactory(List.of(broken, timeouts)).create(original, Locale.ROOT, "/v1", "c");
    assertEquals(500, p.status());
    assertEquals(1, original.getSuppressed().length);
    assertTrue(original.getSuppressed()[0] instanceof NoClassDefFoundError);
    // los mappers posteriores al defectuoso se ejecutan igualmente
    Problem t =
        defaultFactory(List.of(broken, timeouts))
            .create(new TimeoutException("slow"), Locale.ROOT, "/v1", "c");
    assertEquals(409, t.status());
  }
}

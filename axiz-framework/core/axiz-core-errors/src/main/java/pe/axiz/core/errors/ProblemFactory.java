package pe.axiz.core.errors;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Builds RFC 9457 problems from exceptions. Unknown exceptions become a generic 500 and their
 * message is never exposed.
 */
public final class ProblemFactory {

  private static final int MAX_CAUSE_DEPTH = 10;

  private final ErrorCatalog catalog;
  private final ErrorMessageResolver messages;
  private final List<ExceptionMapper> mappers;
  private final Settings settings;
  private final Clock clock;

  /**
   * Factory settings.
   *
   * @param typeBaseUri base URI for the problem type; the code is appended. Empty means {@code
   *     about:blank}
   * @param exposeExternalDetail whether to include the dependency system, status and code
   */
  public record Settings(String typeBaseUri, boolean exposeExternalDetail) {
    public static Settings defaults() {
      return new Settings("", false);
    }
  }

  public ProblemFactory(
      ErrorCatalog catalog,
      ErrorMessageResolver messages,
      List<ExceptionMapper> mappers,
      Settings settings,
      Clock clock) {
    this.catalog = Objects.requireNonNull(catalog);
    this.messages = Objects.requireNonNull(messages);
    this.mappers = List.copyOf(mappers);
    this.settings = Objects.requireNonNull(settings);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Converts any exception into a problem.
   *
   * @param throwable the failure
   * @param locale client locale
   * @param instance request path
   * @param correlationId correlation id
   * @return the problem
   */
  public Problem create(
      Throwable throwable,
      Locale locale,
      @Nullable String instance,
      @Nullable String correlationId) {
    AxizException axiz = toAxizException(throwable);
    return create(axiz, locale, instance, correlationId);
  }

  /** Converts any throwable into an {@link AxizException}, consulting registered mappers. */
  public AxizException toAxizException(Throwable throwable) {
    Throwable current = throwable;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof AxizException axiz) {
        return axiz;
      }
      for (ExceptionMapper mapper : mappers) {
        Optional<AxizException> mapped;
        try {
          mapped = mapper.map(current);
        } catch (RuntimeException | LinkageError e) {
          // a broken mapper must never stop the error response: it is skipped and its failure
          // travels with the original exception, so the log shows both
          throwable.addSuppressed(e);
          continue;
        }
        if (mapped.isPresent()) {
          return mapped.get();
        }
      }
      current = current.getCause();
    }
    return new TechnicalException("Unhandled " + throwable.getClass().getName(), throwable);
  }

  private Problem create(
      AxizException ex, Locale locale, @Nullable String instance, @Nullable String correlationId) {
    ErrorDefinition definition = catalog.find(ex.code()).orElse(ex.definition());
    // The category of the exception wins: a dependency error keeps the category chosen from its
    // status
    ErrorCategory category = ex.category();
    String detail =
        messages
            .resolve(definition.messageKey(), locale, ex.parameters())
            .orElse(definition.title());
    Map<String, Object> external = null;
    ExternalErrorDetail ext = ex.external();
    if (settings.exposeExternalDetail() && ext != null) {
      external = new LinkedHashMap<>();
      external.put("system", ext.system());
      external.put("status", ext.status());
      if (ext.code() != null) {
        external.put("code", ext.code());
      }
    }
    return new Problem(
        typeUri(definition.code()),
        definition.title(),
        category.httpStatus(),
        detail,
        instance,
        definition.code().value(),
        correlationId,
        clock.instant(),
        ex.violations(),
        external);
  }

  /**
   * Problem for an error detected by the framework itself (unknown route, missing header...).
   *
   * @param definition definition
   * @param locale locale
   * @param parameters detail parameters
   * @param instance request path
   * @param correlationId correlation id
   * @return the problem
   */
  public Problem of(
      ErrorDefinition definition,
      Locale locale,
      Map<String, Object> parameters,
      @Nullable String instance,
      @Nullable String correlationId) {
    return create(
        new AxizException(definition, definition.title(), parameters, List.of(), null, null) {
          private static final long serialVersionUID = 1L;
        },
        locale,
        instance,
        correlationId);
  }

  private String typeUri(ErrorCode code) {
    String base = settings.typeBaseUri();
    if (base.isBlank()) {
      return "about:blank";
    }
    return (base.endsWith("/") ? base : base + "/") + code.value().toLowerCase(Locale.ROOT);
  }
}

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
 * Construye problemas RFC 9457 a partir de excepciones. Las excepciones desconocidas se convierten
 * en un 500 genérico y su mensaje nunca se expone.
 */
public final class ProblemFactory {

  private static final int MAX_CAUSE_DEPTH = 10;

  private final ErrorCatalog catalog;
  private final ErrorMessageResolver messages;
  private final List<ExceptionMapper> mappers;
  private final Settings settings;
  private final Clock clock;

  /**
   * Configuración de la fábrica.
   *
   * @param typeBaseUri URI base del tipo de problema; se le agrega el código. Vacío significa
   *     {@code about:blank}
   * @param exposeExternalDetail indica si se incluyen el sistema, el estado y el código de la
   *     dependencia
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
   * Convierte cualquier excepción en un problema.
   *
   * @param throwable el fallo
   * @param locale locale del cliente
   * @param instance ruta de la petición
   * @param correlationId id de correlación
   * @return el problema
   */
  public Problem create(
      Throwable throwable,
      Locale locale,
      @Nullable String instance,
      @Nullable String correlationId) {
    AxizException axiz = toAxizException(throwable);
    return create(axiz, locale, instance, correlationId);
  }

  /**
   * Convierte cualquier throwable en una {@link AxizException}, consultando los mappers
   * registrados.
   */
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
          // un mapper defectuoso nunca debe detener la respuesta de error: se omite y su fallo
          // viaja con la excepción original, de modo que el log muestra ambos
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
    // Prevalece la categoría de la excepción: un error de dependencia conserva la categoría elegida
    // según su estado
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
    return Problem.builder()
        .type(typeUri(definition.code()))
        .title(definition.title())
        .status(category.httpStatus())
        .detail(detail)
        .instance(instance)
        .code(definition.code().value())
        .correlationId(correlationId)
        .timestamp(clock.instant())
        .violations(ex.violations())
        .external(external)
        .build();
  }

  /**
   * Problema para un error detectado por el propio framework (ruta desconocida, cabecera faltante,
   * etc.).
   *
   * @param definition definición
   * @param locale locale
   * @param parameters parámetros del detalle
   * @param instance ruta de la petición
   * @param correlationId id de correlación
   * @return el problema
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

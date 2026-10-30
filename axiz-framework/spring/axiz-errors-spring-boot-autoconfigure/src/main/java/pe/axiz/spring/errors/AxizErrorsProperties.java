package pe.axiz.spring.errors;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorType;

/**
 * Error handling settings ({@code axiz.errors.*}). The service catalog usually comes from the
 * {@code <service>-errors} ConfigMap, mounted as a file and imported by axiz-config.
 *
 * @param typeBaseUri base URI of problem types; empty means {@code about:blank}
 * @param exposeExternalDetail whether responses include the failing dependency name and code
 * @param catalog service error catalog
 */
@ConfigurationProperties("axiz.errors")
public record AxizErrorsProperties(
    @DefaultValue("") String typeBaseUri,
    @DefaultValue("false") boolean exposeExternalDetail,
    @DefaultValue List<CatalogEntry> catalog) {
  public AxizErrorsProperties {
    catalog = List.copyOf(catalog);
  }

  /**
   * One catalog entry.
   *
   * @param code error code, e.g. {@code PAY-ORDER-0001}
   * @param category category (HTTP status)
   * @param type type; defaults to the category's type
   * @param title short title
   * @param messageKey i18n key of the detail; defaults to {@code error.<code>}
   */
  public record CatalogEntry(
      String code,
      ErrorCategory category,
      @Nullable ErrorType type,
      String title,
      @Nullable String messageKey) {}
}

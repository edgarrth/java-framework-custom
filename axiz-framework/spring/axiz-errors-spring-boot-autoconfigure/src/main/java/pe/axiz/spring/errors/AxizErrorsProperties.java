package pe.axiz.spring.errors;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorType;

/**
 * Configuración del manejo de errores ({@code axiz.errors.*}). El catálogo del servicio suele
 * provenir del ConfigMap {@code <service>-errors}, montado como archivo e importado por
 * axiz-config.
 *
 * @param typeBaseUri URI base de los tipos de problema; vacío significa {@code about:blank}
 * @param exposeExternalDetail indica si las respuestas incluyen el nombre y el código de la
 *     dependencia que falla
 * @param catalog catálogo de errores del servicio
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
   * Una entrada del catálogo.
   *
   * @param code código de error, p. ej. {@code PAY-ORDER-0001}
   * @param category categoría (estado HTTP)
   * @param type tipo; por defecto, el tipo de la categoría
   * @param title título breve
   * @param messageKey clave i18n del detalle; por defecto, {@code error.<code>}
   */
  public record CatalogEntry(
      String code,
      ErrorCategory category,
      @Nullable ErrorType type,
      String title,
      @Nullable String messageKey) {}
}

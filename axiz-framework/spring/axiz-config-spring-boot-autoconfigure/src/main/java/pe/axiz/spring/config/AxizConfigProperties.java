package pe.axiz.spring.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration conventions ({@code axiz.config.*}).
 *
 * @param environment logical environment ({@code dev}, {@code qa}, {@code prod}); used in the
 *     Parameter Store path
 * @param required properties that must be present at startup; the application fails otherwise
 * @param parameterStore Parameter Store import settings
 */
@ConfigurationProperties("axiz.config")
public record AxizConfigProperties(
    @DefaultValue("default") String environment,
    @DefaultValue List<String> required,
    @DefaultValue ParameterStore parameterStore) {
  public AxizConfigProperties {
    required = List.copyOf(required);
  }

  /**
   * Parameter Store import.
   *
   * @param enabled whether {@code optional:aws-parameterstore:} is imported
   * @param prefix root of the parameter hierarchy
   */
  public record ParameterStore(
      @DefaultValue("false") boolean enabled, @DefaultValue("/axiz") String prefix) {}
}

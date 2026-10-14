package pe.axiz.spring.sensitive;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Masking policy ({@code axiz.sensitive.*}).
 *
 * @param response minimum level masked in API responses
 * @param log minimum level masked in logs
 * @param audit minimum level masked in audit events
 * @param fullMaskLevel from this level on, values are always fully masked
 * @param keys extra property or header names treated as sensitive, with their level and mask
 */
@ConfigurationProperties("axiz.sensitive")
public record AxizSensitiveProperties(
    @DefaultValue("MEDIUM") SensitivityLevel response,
    @DefaultValue("LOW") SensitivityLevel log,
    @DefaultValue("MEDIUM") SensitivityLevel audit,
    @DefaultValue("CRITICAL") SensitivityLevel fullMaskLevel,
    @DefaultValue Map<String, Key> keys) {

  /**
   * A sensitive key.
   *
   * @param level sensitivity
   * @param mask mask
   */
  public record Key(
      @DefaultValue("HIGH") SensitivityLevel level, @DefaultValue("PARTIAL") MaskType mask) {}
}

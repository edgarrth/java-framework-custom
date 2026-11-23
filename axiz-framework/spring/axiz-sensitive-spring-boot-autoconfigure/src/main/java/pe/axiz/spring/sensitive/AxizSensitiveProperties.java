package pe.axiz.spring.sensitive;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Política de enmascaramiento ({@code axiz.sensitive.*}).
 *
 * @param response nivel mínimo enmascarado en las respuestas de la API
 * @param log nivel mínimo enmascarado en los logs
 * @param audit nivel mínimo enmascarado en los eventos de auditoría
 * @param fullMaskLevel a partir de este nivel, los valores siempre se enmascaran por completo
 * @param keys nombres adicionales de propiedades o cabeceras tratados como sensibles, con su nivel
 *     y su máscara
 * @param detectPan detecta candidatos a PAN en logs y auditoría sin importar el nombre de la
 *     propiedad
 */
@ConfigurationProperties("axiz.sensitive")
public record AxizSensitiveProperties(
    @DefaultValue("MEDIUM") SensitivityLevel response,
    @DefaultValue("LOW") SensitivityLevel log,
    @DefaultValue("MEDIUM") SensitivityLevel audit,
    @DefaultValue("CRITICAL") SensitivityLevel fullMaskLevel,
    @DefaultValue Map<String, Key> keys,
    @DefaultValue("true") boolean detectPan) {
  public AxizSensitiveProperties {
    keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
  }

  /**
   * Clave sensible.
   *
   * @param level sensibilidad
   * @param mask máscara
   */
  public record Key(
      @DefaultValue("HIGH") SensitivityLevel level, @DefaultValue("PARTIAL") MaskType mask) {}
}

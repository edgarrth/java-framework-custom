package pe.axiz.spring.sequence;

import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.sequence.ResetPolicy;

/**
 * Secuencias de negocio ({@code axiz.sequence.*}), p. ej. números de operación {@code
 * OP-2026-00001234}.
 *
 * @param store {@code jdbc} (Aurora; R2DBC nativo en un servicio reactivo), {@code r2dbc} o {@code
 *     dynamodb}
 * @param sequences secuencias por nombre
 */
@ConfigurationProperties("axiz.sequence")
public record AxizSequenceProperties(
    @DefaultValue("jdbc") String store, @DefaultValue Map<String, Spec> sequences) {
  public AxizSequenceProperties {
    sequences = Collections.unmodifiableMap(new LinkedHashMap<>(sequences));
  }

  /**
   * Una secuencia.
   *
   * @param prefix prefijo fijo
   * @param width dígitos rellenados con ceros
   * @param reset periodo de reinicio
   * @param separator separador entre prefijo, periodo y número
   * @param zone zona del periodo
   * @param blockSize valores reservados por viaje de ida y vuelta (puede haber huecos al reiniciar)
   */
  public record Spec(
      @DefaultValue("") String prefix,
      @DefaultValue("8") int width,
      @DefaultValue("NEVER") ResetPolicy reset,
      @DefaultValue("-") String separator,
      @DefaultValue("America/Lima") ZoneId zone,
      @DefaultValue("1") int blockSize) {}
}

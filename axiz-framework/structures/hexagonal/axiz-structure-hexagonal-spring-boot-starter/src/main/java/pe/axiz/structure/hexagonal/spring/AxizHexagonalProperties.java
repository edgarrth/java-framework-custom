package pe.axiz.structure.hexagonal.spring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del estilo hexagonal ({@code axiz.hexagonal.*}).
 *
 * @param events publicación de eventos de dominio mediante el outbox
 */
@ConfigurationProperties("axiz.hexagonal")
public record AxizHexagonalProperties(@DefaultValue Events events) {

  /**
   * Enrutamiento de los eventos de dominio.
   *
   * @param destination destino lógico por defecto
   * @param destinations destino por tipo de evento
   * @param typeSuffix sufijo de versión de los tipos de evento, por ejemplo {@code .v1}
   */
  public record Events(
      @DefaultValue("domain-events") String destination,
      @DefaultValue Map<String, String> destinations,
      @DefaultValue(".v1") String typeSuffix) {
    public Events {
      destinations = Collections.unmodifiableMap(new LinkedHashMap<>(destinations));
    }
  }
}

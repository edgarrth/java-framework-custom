package pe.axiz.spring.observability;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Convenciones de observabilidad ({@code axiz.observability.*}).
 *
 * @param deniedAttributes claves de atributo adicionales que nunca se exportan (protección de PII)
 * @param ignoredPaths rutas entrantes que no se observan (sondas, scraping de métricas)
 * @param contextAttributes indica si se añaden a los spans el id de correlación, el canal y el
 *     tenant
 */
@ConfigurationProperties("axiz.observability")
public record AxizObservabilityProperties(
    @DefaultValue Set<String> deniedAttributes,
    @DefaultValue({"/actuator"}) List<String> ignoredPaths,
    @DefaultValue("true") boolean contextAttributes) {
  public AxizObservabilityProperties {
    deniedAttributes = Collections.unmodifiableSet(new LinkedHashSet<>(deniedAttributes));
    ignoredPaths = List.copyOf(ignoredPaths);
  }
}

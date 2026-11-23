package pe.axiz.spring.secrets.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Archivos montados y variables de entorno; no se selecciona implícitamente ningún proveedor
 * remoto.
 */
@ConfigurationProperties("axiz.secrets")
public record AxizSecretsFileProperties(
    @DefaultValue("/var/run/secrets/axiz") String directory,
    @DefaultValue("true") boolean environment) {
  public AxizSecretsFileProperties {
    if (directory.isBlank() || directory.contains("${"))
      throw new IllegalArgumentException("axiz.secrets.directory must not be blank or unresolved");
  }
}

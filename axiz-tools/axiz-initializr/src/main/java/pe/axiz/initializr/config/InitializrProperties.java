package pe.axiz.initializr.config;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del initializr ({@code axiz.initializr.*}).
 *
 * @param frameworkVersion versión de axiz de los proyectos generados
 * @param githubApi URL base de la API de GitHub (GitHub Enterprise usa la suya)
 * @param githubOrganization organización donde se crean los repositorios; la cuenta del usuario
 *     cuando está vacía
 * @param maxFiles cantidad máxima de archivos que se suben a un repositorio nuevo
 * @param audience audiencia que deben llevar los tokens bearer para invocar la API (se rechazan los
 *     tokens del mismo proveedor de identidad emitidos para otras API)
 */
@ConfigurationProperties("axiz.initializr")
public record InitializrProperties(
    @DefaultValue("2.0.0") String frameworkVersion,
    @DefaultValue("https://api.github.com") URI githubApi,
    @Nullable String githubOrganization,
    @DefaultValue("2000") int maxFiles,
    @DefaultValue(SecurityConfiguration.DEFAULT_AUDIENCE) String audience) {
  public InitializrProperties {
    if (!"https".equals(githubApi.getScheme())
        || githubApi.getHost() == null
        || githubApi.getUserInfo() != null
        || githubApi.getQuery() != null
        || githubApi.getFragment() != null)
      throw new IllegalArgumentException(
          "GitHub API requires a configured HTTPS origin without credentials");
    if (maxFiles < 1 || maxFiles > 10000 || audience.isBlank())
      throw new IllegalArgumentException("Invalid Initializr limits or audience");
  }
}

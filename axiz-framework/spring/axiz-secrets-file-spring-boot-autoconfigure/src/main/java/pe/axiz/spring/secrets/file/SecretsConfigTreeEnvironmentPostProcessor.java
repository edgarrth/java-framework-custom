package pe.axiz.spring.secrets.file;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Importa los archivos de secretos montados como un config tree ({@code optional:configtree:}), de
 * modo que un archivo llamado {@code spring.datasource.password} se convierte en esa propiedad. Se
 * ejecuta antes de la carga de config data.
 */
public class SecretsConfigTreeEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizSecretsImports";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    String dir = env.getProperty("axiz.secrets.directory", "/var/run/secrets/axiz");
    if (!env.getProperty("axiz.secrets.enabled", Boolean.class, true)
        || !env.getProperty("axiz.secrets.config-tree", Boolean.class, true)
        || !Files.isDirectory(Path.of(dir))) {
      return;
    }
    String entry = "optional:configtree:" + (dir.endsWith("/") ? dir : dir + "/");
    String existing = env.getProperty("spring.config.import");
    if (existing != null && existing.contains(entry)) {
      return;
    }
    String merged = StringUtils.hasText(existing) ? entry + "," + existing : entry;
    env.getPropertySources()
        .addFirst(new MapPropertySource(SOURCE, Map.of("spring.config.import", merged)));
  }

  @Override
  public int getOrder() {
    // antes de las convenciones de configuración (que conservan las importaciones existentes) y de
    // la carga de config data de Boot
    return Ordered.HIGHEST_PRECEDENCE + 4;
  }
}

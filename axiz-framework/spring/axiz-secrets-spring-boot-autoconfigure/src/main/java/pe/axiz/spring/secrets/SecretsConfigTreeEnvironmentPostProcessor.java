package pe.axiz.spring.secrets;

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
 * Imports the mounted secret files as a config tree ({@code optional:configtree:}), so a file named
 * {@code spring.datasource.password} becomes that property. Runs before config data loading.
 */
public class SecretsConfigTreeEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "axizSecretsImports";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    String dir = env.getProperty("axiz.secrets.directory", "/var/run/secrets/axiz");
    if (!env.getProperty("axiz.secrets.config-tree", Boolean.class, true)
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
    // before the config conventions (which keep existing imports) and Boot's config data loading
    return Ordered.HIGHEST_PRECEDENCE + 4;
  }
}

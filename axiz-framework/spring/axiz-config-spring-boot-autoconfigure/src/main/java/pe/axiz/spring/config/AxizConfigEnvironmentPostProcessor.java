package pe.axiz.spring.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;
import pe.axiz.core.commons.Environments;

/** Imports mounted ConfigMaps before Config Data, after provider conventions; no Kubernetes API. */
public class AxizConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Runs before Boot's config data processing ({@code HIGHEST_PRECEDENCE + 10}). */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 6;

  /** Where the chart mounts the ConfigMaps. */
  public static final String DEFAULT_DIRECTORY = "/etc/axiz/config";

  static final String IMPORTS = "axizConfigImports";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    var selection = new ConfigBootstrapSelection(env);
    env.getPropertySources().addLast(selection);
    if (!env.getProperty("axiz.config.enabled", Boolean.class, true)) {
      return;
    }
    boolean cluster =
        inCluster(env) && !Environments.allowsInMemoryState(selection.getSource().environment());
    if (cluster) ConfigurationEnvironment.requireEnvironment(selection.getSource().environment());
    List<String> imports = new ArrayList<>();

    if (cluster) {
      String dir = env.getProperty("axiz.config.directory", DEFAULT_DIRECTORY);
      String base = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
      imports.add("optional:file:" + base + "/common/application.yaml");
      imports.add("file:" + base + "/service/application.yaml");
      imports.add("file:" + base + "/errors/application.yaml");
    }
    ConfigurationImports.append(env, IMPORTS, imports);
  }

  static boolean inCluster(ConfigurableEnvironment env) {
    String platform = env.getProperty("spring.main.cloud-platform");
    if (platform != null) {
      return "kubernetes".equalsIgnoreCase(platform);
    }
    return StringUtils.hasText(env.getProperty("KUBERNETES_SERVICE_HOST"))
        && StringUtils.hasText(env.getProperty("KUBERNETES_SERVICE_PORT"));
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}

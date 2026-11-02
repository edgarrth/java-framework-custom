package pe.axiz.spring.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Applies the configuration conventions before config data is loaded.
 *
 * <ul>
 *   <li>Inside Kubernetes (and outside the {@code local} and {@code test} profiles) it imports the
 *       ConfigMaps the chart mounts as files under {@code axiz.config.directory} (default {@value
 *       #DEFAULT_DIRECTORY}): {@code common/application.yaml} (ConfigMap {@code axiz-common},
 *       optional), then {@code service/application.yaml} ({@code <app>}) and {@code
 *       errors/application.yaml} ({@code <app>-errors}), both required so a missing ConfigMap stops
 *       the startup. Later files win. Reading files instead of the Kubernetes API means the pods
 *       need no service account token and no RBAC: a compromised pod cannot list the ConfigMaps of
 *       the namespace. A ConfigMap change rolls the pods (checksum annotations).
 *   <li>With {@code axiz.config.parameter-store.enabled=true} it imports {@code
 *       optional:aws-parameterstore:/axiz/<environment>/<app>/} below the ConfigMaps.
 *   <li>Spring Cloud AWS Parameter Store follows {@code axiz.config.parameter-store.enabled} (false
 *       by default): otherwise it builds an {@code SsmClient} at startup that nothing uses, and
 *       that fails without an AWS region (tests, laptops, generated projects without AWS).
 *   <li>The Spring Cloud AWS Secrets Manager integration is off by default: axiz reads secrets
 *       through {@code axiz.secrets} (its own resolver, enabled explicitly), and that integration
 *       would otherwise build a {@code SecretsManagerClient} as soon as the SDK is on the
 *       classpath. A service that imports {@code aws-secretsmanager:} config data turns it on with
 *       {@code spring.cloud.aws.secretsmanager.enabled=true}.
 * </ul>
 *
 * Values set explicitly by the service always win: the conventions are added with the lowest
 * precedence, and existing {@code spring.config.import} entries are kept.
 */
public class AxizConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Runs before Boot's config data processing ({@code HIGHEST_PRECEDENCE + 10}). */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

  /** Where the chart mounts the ConfigMaps. */
  public static final String DEFAULT_DIRECTORY = "/etc/axiz/config";

  static final String DEFAULTS = "axizConfigDefaults";
  static final String IMPORTS = "axizConfigImports";
  private static final List<String> LOCAL_PROFILES = List.of("local", "test");

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    if (!env.getProperty("axiz.config.enabled", Boolean.class, true)) {
      return;
    }
    String app = env.getProperty("spring.application.name", "application");
    boolean cluster = inCluster(env) && !localProfile(env);
    Map<String, Object> defaults = new LinkedHashMap<>();
    List<String> imports = new ArrayList<>();

    if (env.getProperty("axiz.config.parameter-store.enabled", Boolean.class, false)) {
      String environment =
          env.getProperty("axiz.config.environment", "default").toLowerCase(Locale.ROOT);
      String prefix = env.getProperty("axiz.config.parameter-store.prefix", "/axiz");
      String stableApp = env.getProperty("axiz.config.parameter-store.application", app);
      if (!stableApp.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
        throw new IllegalArgumentException("Invalid axiz.config.parameter-store.application");
      }
      imports.add(
          "optional:aws-parameterstore:" + prefix + "/" + environment + "/" + stableApp + "/");
    }
    if (cluster) {
      String dir = env.getProperty("axiz.config.directory", DEFAULT_DIRECTORY);
      String base = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
      imports.add("optional:file:" + base + "/common/application.yaml");
      imports.add("file:" + base + "/service/application.yaml");
      imports.add("file:" + base + "/errors/application.yaml");
    }
    // a placeholder, resolved when Spring Cloud AWS binds it: the service's own files count too
    defaults.put(
        "spring.cloud.aws.parameterstore.enabled", "${axiz.config.parameter-store.enabled:false}");
    defaults.put("spring.cloud.aws.secretsmanager.enabled", "false");
    env.getPropertySources().addLast(new MapPropertySource(DEFAULTS, defaults));

    if (!imports.isEmpty()) {
      List<String> merged = new ArrayList<>();
      String existing = env.getProperty("spring.config.import");
      if (StringUtils.hasText(existing)) {
        merged.addAll(
            Arrays.stream(existing.split(",", -1))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
      }
      for (String i : imports) {
        String target = i.replace("optional:", "");
        if (merged.stream().noneMatch(e -> e.replace("optional:", "").equals(target))) {
          merged.add(i);
        }
      }
      env.getPropertySources()
          .addFirst(
              new MapPropertySource(
                  IMPORTS, Map.of("spring.config.import", String.join(",", merged))));
    }
  }

  static boolean inCluster(ConfigurableEnvironment env) {
    String platform = env.getProperty("spring.main.cloud-platform");
    if (platform != null) {
      return "kubernetes".equalsIgnoreCase(platform);
    }
    return StringUtils.hasText(env.getProperty("KUBERNETES_SERVICE_HOST"))
        && StringUtils.hasText(env.getProperty("KUBERNETES_SERVICE_PORT"));
  }

  private static boolean localProfile(ConfigurableEnvironment env) {
    List<String> active = new ArrayList<>(Arrays.asList(env.getActiveProfiles()));
    String prop = env.getProperty("spring.profiles.active");
    if (prop != null) {
      active.addAll(
          Arrays.stream(prop.split(",", -1)).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
    return active.stream().anyMatch(LOCAL_PROFILES::contains);
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}

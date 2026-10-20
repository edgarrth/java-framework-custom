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
 *   <li>Inside Kubernetes (and outside the {@code local} and {@code test} profiles) it imports
 *       {@code kubernetes:} with the sources {@code axiz-common}, {@code <app>} and {@code
 *       <app>-errors}, fail-fast enabled, reload disabled and Secrets API reading disabled.
 *   <li>With {@code axiz.config.parameter-store.enabled=true} it imports {@code
 *       optional:aws-parameterstore:/axiz/<environment>/<app>/} below the ConfigMaps.
 *   <li>Outside the cluster the Kubernetes integration is switched off.
 *   <li>Spring Cloud AWS Parameter Store follows {@code axiz.config.parameter-store.enabled} (false
 *       by default): otherwise it builds an {@code SsmClient} at startup that nothing uses, and that
 *       fails without an AWS region (tests, laptops, generated projects without AWS).
 * </ul>
 *
 * Values set explicitly by the service always win: the conventions are added with the lowest
 * precedence, and existing {@code spring.config.import} entries are kept.
 */
public class AxizConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Runs before Boot's config data processing ({@code HIGHEST_PRECEDENCE + 10}). */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

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
      imports.add("optional:aws-parameterstore:" + prefix + "/" + environment + "/" + app + "/");
    }
    if (cluster) {
      defaults.put("spring.cloud.kubernetes.config.fail-fast", "true");
      defaults.put("spring.cloud.kubernetes.config.retry.max-attempts", "6");
      defaults.put("spring.cloud.kubernetes.config.sources[0].name", "axiz-common");
      defaults.put("spring.cloud.kubernetes.config.sources[1].name", app);
      defaults.put("spring.cloud.kubernetes.config.sources[2].name", app + "-errors");
      defaults.put("spring.cloud.kubernetes.secrets.enabled", "false");
      defaults.put("spring.cloud.kubernetes.reload.enabled", "false");
      imports.add("kubernetes:");
    } else {
      defaults.put("spring.cloud.kubernetes.enabled", "false");
      defaults.put("spring.cloud.kubernetes.config.enabled", "false");
      defaults.put("spring.cloud.kubernetes.secrets.enabled", "false");
    }
    // a placeholder, resolved when Spring Cloud AWS binds it: the service's own files count too
    defaults.put(
        "spring.cloud.aws.parameterstore.enabled", "${axiz.config.parameter-store.enabled:false}");
    env.getPropertySources().addLast(new MapPropertySource(DEFAULTS, defaults));

    if (!imports.isEmpty()) {
      List<String> merged = new ArrayList<>();
      String existing = env.getProperty("spring.config.import");
      if (StringUtils.hasText(existing)) {
        merged.addAll(
            Arrays.stream(existing.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
      }
      for (String i : imports) {
        if (merged.stream().noneMatch(e -> e.endsWith(i.replace("optional:", "")))) {
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
      active.addAll(Arrays.stream(prop.split(",")).map(String::trim).toList());
    }
    return active.stream().anyMatch(LOCAL_PROFILES::contains);
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}

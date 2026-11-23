package pe.axiz.spring.r2dbc.aws;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Indica al bootstrap genérico de base de datos que IAM aporta una contraseña dinámica. */
public final class AxizR2dbcAwsEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 1;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    if (env.getProperty("axiz.r2dbc.iam.enabled", Boolean.class, false)) {
      env.getPropertySources()
          .addLast(
              new MapPropertySource(
                  "axizR2dbcAwsDefaults", Map.of("axiz.r2dbc.password-mode", "dynamic")));
    }
  }
}

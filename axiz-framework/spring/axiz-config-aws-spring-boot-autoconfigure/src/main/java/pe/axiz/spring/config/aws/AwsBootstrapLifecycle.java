package pe.axiz.spring.config.aws;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Covers config-data failures before bootstrap resources can be transferred to Spring. */
public final class AwsBootstrapLifecycle implements SpringApplicationRunListener {
  private @Nullable ConfigurableBootstrapContext bootstrap;

  public AwsBootstrapLifecycle(SpringApplication application, String[] arguments) {}

  @Override
  public void starting(ConfigurableBootstrapContext context) {
    bootstrap = context;
  }

  @Override
  public void failed(@Nullable ConfigurableApplicationContext context, Throwable failure) {
    if (bootstrap != null && bootstrap.isRegistered(AwsBootstrapOwner.class)) {
      var owner = bootstrap.get(AwsBootstrapOwner.class);
      if (owner != null) owner.failed();
    }
  }
}

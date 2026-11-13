package pe.axiz.spring.foundation.aws;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;

/** Validate effective properties after Config Data; bootstrap validates before its first access. */
public final class AwsCredentialEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (ClassUtils.isPresent(
        "pe.axiz.adapter.aws.identity.AwsCredentialSource", application.getClassLoader()))
      AwsCredentialConfiguration.validate(Binder.get(environment));
  }
}

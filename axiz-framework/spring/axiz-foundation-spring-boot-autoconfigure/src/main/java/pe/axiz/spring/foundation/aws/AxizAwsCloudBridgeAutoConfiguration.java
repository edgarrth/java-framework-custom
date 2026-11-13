package pe.axiz.spring.foundation.aws;

import io.awspring.cloud.autoconfigure.AwsClientProperties;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import io.awspring.cloud.autoconfigure.core.AwsProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AwsTransportSecurity;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;

/** Explicit bridge: Cloud AWS never chooses a scoped provider by generic type injection. */
@AutoConfiguration(
    after = AxizAwsIdentityAutoConfiguration.class,
    beforeName =
        "io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreAutoConfiguration")
@ConditionalOnClass(
    name = {
      "io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer",
      "pe.axiz.adapter.aws.identity.AwsCredentialSource"
    })
@EnableConfigurationProperties(AwsProperties.class)
public class AxizAwsCloudBridgeAutoConfiguration {
  protected AxizAwsCloudBridgeAutoConfiguration() {}

  @Bean(destroyMethod = "close")
  @Lazy
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @Qualifier("axizAwsCloudGlobal") AwsCredentialSource axizAwsCloudGlobal(AwsIdentity identity) {
    return identity.globalSource();
  }

  @Bean
  @Lazy
  @ConditionalOnMissingBean(AwsClientBuilderConfigurer.class)
  AwsClientBuilderConfigurer axizAwsCloudConfigurer(
      @Qualifier("axizAwsCloudGlobal") AwsCredentialSource source,
      AwsRegionProvider region,
      AwsProperties properties,
      Environment environment,
      ObjectProvider<AwsConnectionDetails> connectionDetails) {
    validate(connectionDetails.getIfAvailable());
    var policy = TransportSecurityPolicy.from(environment::getProperty);
    return new ManagedConfigurer(source, region, properties, policy);
  }

  @Bean
  SmartInitializingSingleton axizAwsConnectionIdentityValidated(
      ObjectProvider<AwsConnectionDetails> details) {
    return () -> validate(details.getIfAvailable());
  }

  private static void validate(@Nullable AwsConnectionDetails details) {
    if (details != null
        && (StringUtils.hasText(details.getAccessKey())
            || StringUtils.hasText(details.getSecretKey())))
      throw new IllegalStateException(
          "AWS connection details cannot replace managed workload identity;"
              + " declare an explicit application credential provider");
  }

  private static final class ManagedConfigurer extends AwsClientBuilderConfigurer {
    private final TransportSecurityPolicy policy;
    private final AwsProperties properties;

    ManagedConfigurer(
        AwsCredentialSource source,
        AwsRegionProvider region,
        AwsProperties properties,
        TransportSecurityPolicy policy) {
      super(source.provider(), region, properties);
      this.policy = policy;
      this.properties = properties;
    }

    @Override
    public <T extends AwsClientBuilder<T, ?>> T configure(
        T builder, @Nullable AwsClientProperties settings, @Nullable AwsConnectionDetails details) {
      validate(details);
      var endpoint =
          settings != null && settings.getEndpoint() != null
              ? settings.getEndpoint()
              : properties.getEndpoint();
      if (endpoint != null) policy.validate(endpoint, "Spring Cloud AWS effective endpoint");
      else AwsTransportSecurity.validateOverrides("ssm", policy);
      T result = super.configure(builder, settings, details);
      AwsTransportSecurity.apply(result, policy);
      return result;
    }
  }
}

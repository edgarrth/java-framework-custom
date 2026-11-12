package pe.axiz.spring.foundation.aws;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/** Declarative, passive infrastructure; AWS providers and the SDK source remain lazy. */
@AutoConfiguration
@ConditionalOnClass(
    name = {
      "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider",
      "pe.axiz.adapter.aws.identity.AwsCredentialSource"
    })
public class AxizAwsIdentityAutoConfiguration {
  /** Spring configuration with an early, static infrastructure factory. */
  protected AxizAwsIdentityAutoConfiguration() {}

  @Bean(name = AwsIdentity.BEAN_NAME, destroyMethod = "close")
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static AwsIdentity axizAwsIdentity(ConfigurableListableBeanFactory beans) {
    return new AwsIdentity(beans);
  }
}

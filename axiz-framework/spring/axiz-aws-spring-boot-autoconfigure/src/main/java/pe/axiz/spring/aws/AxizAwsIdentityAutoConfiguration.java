package pe.axiz.spring.aws;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import pe.axiz.spring.foundation.AxizTransportProperties;

/**
 * Infraestructura declarativa y pasiva; los proveedores AWS y la fuente del SDK siguen siendo
 * diferidos.
 */
@AutoConfiguration
@EnableConfigurationProperties({
  AxizAwsAsyncTransportProperties.class,
  AxizTransportProperties.class
})
@ConditionalOnClass(
    name = {
      "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider",
      "pe.axiz.adapter.aws.identity.AwsCredentialSource"
    })
public class AxizAwsIdentityAutoConfiguration {
  /** Configuración de Spring con una fábrica de infraestructura estática y temprana. */
  protected AxizAwsIdentityAutoConfiguration() {}

  @Bean(name = AwsIdentity.BEAN_NAME, destroyMethod = "close")
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static AwsIdentity axizAwsIdentity(
      ConfigurableListableBeanFactory beans, Environment environment) {
    return new AwsIdentity(beans, environment);
  }
}

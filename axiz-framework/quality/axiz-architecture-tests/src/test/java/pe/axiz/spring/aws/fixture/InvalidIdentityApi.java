package pe.axiz.spring.aws.fixture;

import java.util.function.Supplier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;

public final class InvalidIdentityApi {
  public Supplier<ConfigurableListableBeanFactory> expose(ConfigurableListableBeanFactory beans) {
    return () -> beans;
  }

  public void mutate(ConfigurableListableBeanFactory beans) {
    ((BeanDefinitionRegistry) beans)
        .registerBeanDefinition("runtime", new RootBeanDefinition(Object.class));
    beans.isCurrentlyInCreation("unrelated");
  }
}

package pe.axiz.spring.tokenization.vault.http;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.spring.tokenization.vault.VerifiedTokenCaller;

/**
 * Selecciona solo la pila HTTP de la aplicación; sin interruptor de desactivación desprotegido para
 * las rutas del vault.
 */
@AutoConfiguration(
    afterName = "pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration")
@ConditionalOnWebApplication
public class AxizTokenizationVaultHttpAutoConfiguration {
  @Bean
  VaultHttpPolicy axizVaultHttpPolicy(Environment environment) {
    boolean allow =
        environment.getProperty(
            "axiz.tokenization.vault.http.allow-insecure", Boolean.class, false);
    if (allow
        && !Environments.allowsInMemoryState(environment.getProperty("axiz.config.environment")))
      throw new IllegalStateException("Insecure token vault HTTP is restricted to local/test/ci");
    if (environment.getProperty("spring.mvc.log-request-details", Boolean.class, false)
        || environment.getProperty("spring.codec.log-request-details", Boolean.class, false))
      throw new IllegalStateException("Token vault HTTP prohibits request detail logging");
    return new VaultHttpPolicy(allow);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  static class Servlet {
    @Bean
    ServletVaultController axizServletVaultController(
        VaultOperations engine, VerifiedTokenCaller callers, VaultHttpPolicy policy) {
      return new ServletVaultController(engine, callers, policy);
    }

    @Bean
    ServletVaultGuard axizServletVaultGuard(VaultHttpPolicy policy) {
      return new ServletVaultGuard(policy);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  static class Reactive {
    @Bean
    ReactiveVaultController axizReactiveVaultController(
        AsyncVaultOperations engine, VerifiedTokenCaller callers, VaultHttpPolicy policy) {
      return new ReactiveVaultController(engine, callers, policy);
    }

    @Bean
    ReactiveVaultGuard axizReactiveVaultGuard(VaultHttpPolicy policy) {
      return new ReactiveVaultGuard(policy);
    }
  }
}

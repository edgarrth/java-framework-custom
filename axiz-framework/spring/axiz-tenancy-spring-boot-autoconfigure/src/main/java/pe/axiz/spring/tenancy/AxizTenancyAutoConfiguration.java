package pe.axiz.spring.tenancy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-tenancy, solo cuando {@code axiz.tenancy.enabled=true}: tenant tomado del token o de una
 * cabecera de confianza, vinculado a la solicitud, propagado a los logs, las llamadas salientes y
 * los mensajes, y aplicado a las entidades {@code @TenantId} de Hibernate.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.security.AxizSecurityAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.tenancy.enabled")
@EnableConfigurationProperties(AxizTenancyProperties.class)
public class AxizTenancyAutoConfiguration {

  /**
   * Se ejecuta después de la cadena de filtros de Spring Security para que se conozca el tenant del
   * token.
   */
  public static final int FILTER_ORDER = -100 + 10;

  @Bean
  @ConditionalOnMissingBean
  TenantFilterSupport axizTenantFilterSupport(AxizTenancyProperties properties) {
    return new TenantFilterSupport(properties);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  static class ServletConfiguration {

    @Bean
    FilterRegistrationBean<TenantFilter> axizTenantFilter(TenantFilterSupport support) {
      FilterRegistrationBean<TenantFilter> registration =
          new FilterRegistrationBean<>(new TenantFilter(support));
      registration.setOrder(FILTER_ORDER);
      return registration;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  static class ReactiveConfiguration {

    @Bean
    TenantWebFilter axizTenantWebFilter(TenantFilterSupport support) {
      return new TenantWebFilter(support);
    }
  }
}

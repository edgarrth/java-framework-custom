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
 * Multi-tenancy, only when {@code axiz.tenancy.enabled=true}: tenant from the token or a trusted
 * header, bound for the request, propagated to logs, outbound calls and messages, and applied to
 * Hibernate {@code @TenantId} entities.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.security.AxizSecurityAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.tenancy.enabled")
@EnableConfigurationProperties(AxizTenancyProperties.class)
public class AxizTenancyAutoConfiguration {

  /** Runs after Spring Security's filter chain so the token tenant is known. */
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

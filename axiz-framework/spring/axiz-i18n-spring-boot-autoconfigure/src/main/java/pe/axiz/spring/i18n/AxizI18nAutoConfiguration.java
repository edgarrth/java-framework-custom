package pe.axiz.spring.i18n;

import java.util.HashMap;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.errors.ErrorMessageResolver;
import pe.axiz.core.i18n.BundleMessageResolver;
import pe.axiz.core.i18n.CompositeMessageResolver;
import pe.axiz.core.i18n.LocaleResolver;
import pe.axiz.core.i18n.MessageResolver;

/**
 * Message bundles and {@code Accept-Language} handling. Service bundles take precedence over the
 * framework bundle {@code axiz/i18n/messages}; error details are localized through the same
 * resolver.
 */
@AutoConfiguration(
    beforeName = {
      "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration",
      "org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration",
      "pe.axiz.spring.errors.AxizErrorsAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.i18n.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizI18nProperties.class)
public class AxizI18nAutoConfiguration {

  /** Base name of the messages shipped with the framework. */
  public static final String FRAMEWORK_BUNDLE = "axiz/i18n/messages";

  @Bean
  @ConditionalOnMissingBean
  MessageResolver axizMessageResolver(AxizI18nProperties properties) {
    ClassLoader cl = AxizI18nAutoConfiguration.class.getClassLoader();
    return new CompositeMessageResolver(
        List.of(
            new BundleMessageResolver(properties.basenames(), properties.defaultLocale(), cl),
            new BundleMessageResolver(List.of(FRAMEWORK_BUNDLE), properties.defaultLocale(), cl)));
  }

  @Bean
  @ConditionalOnMissingBean
  LocaleResolver axizLocaleResolver(AxizI18nProperties properties) {
    return new LocaleResolver(properties.supportedLocales(), properties.defaultLocale());
  }

  @Bean
  @ConditionalOnMissingBean
  ErrorMessageResolver axizErrorMessageResolver(MessageResolver messages) {
    return (key, locale, parameters) ->
        messages.find(key, locale, new HashMap<String, Object>(parameters));
  }

  /** Spring MVC: replaces the default {@code localeResolver}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  static class ServletConfiguration {

    @Bean(name = "localeResolver")
    @ConditionalOnMissingBean(name = "localeResolver")
    AcceptLanguageLocaleResolver axizServletLocaleResolver(LocaleResolver resolver) {
      return new AcceptLanguageLocaleResolver(resolver);
    }
  }

  /** WebFlux: replaces the default {@code localeContextResolver}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  static class ReactiveConfiguration {

    @Bean(name = "localeContextResolver")
    @ConditionalOnMissingBean(name = "localeContextResolver")
    AcceptLanguageLocaleContextResolver axizLocaleContextResolver(LocaleResolver resolver) {
      return new AcceptLanguageLocaleContextResolver(resolver);
    }
  }
}

package pe.axiz.spring.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.HeaderCatalog;
import pe.axiz.core.commons.HeaderDefinition;
import pe.axiz.core.errors.ProblemFactory;
import tools.jackson.core.StreamReadConstraints;

/**
 * Web conventions for servlet and reactive applications: request context, header catalog, paging,
 * CORS, API versioning and JSON customization (Boot's mapper is customized, never replaced).
 */
@AutoConfiguration(
    afterName = "pe.axiz.spring.errors.AxizErrorsAutoConfiguration",
    beforeName = {
      "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration",
      "org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration"
    })
@ConditionalOnWebApplication
@ConditionalOnBooleanProperty(name = "axiz.web.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizWebProperties.class)
public class AxizWebAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  HeaderCatalog axizHeaderCatalog(
      AxizWebProperties properties, ObjectProvider<HeaderDefinition> extra) {
    List<HeaderDefinition> all = new ArrayList<>();
    properties.headers().forEach(h -> all.add(h.toDefinition()));
    extra.orderedStream().forEach(all::add);
    return HeaderCatalog.of(all);
  }

  @Bean
  @ConditionalOnMissingBean
  RequestMetadataResolver axizRequestMetadataResolver(
      AxizWebProperties properties, HeaderCatalog catalog, ObjectProvider<Clock> clock) {
    return new RequestMetadataResolver(
        catalog,
        properties.excludedPaths(),
        properties.channelHeader(),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  PageRequestParser axizPageRequestParser(AxizWebProperties properties) {
    return new PageRequestParser(
        properties.pagination().defaultSize(), properties.pagination().maxSize());
  }

  /** Jackson: omits nulls unless configured otherwise. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = "org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer")
  static class JacksonConfiguration {

    @Bean
    JsonFactoryBuilderCustomizer axizJsonReadConstraints(AxizWebProperties properties) {
      AxizWebProperties.Json json = properties.json();
      return builder ->
          builder.streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNumberLength(json.maxNumberLength())
                  .maxStringLength(json.maxStringLength())
                  .maxNestingDepth(json.maxNestingDepth())
                  .build());
    }

    @Bean
    JsonMapperBuilderCustomizer axizJsonCustomizer(AxizWebProperties properties) {
      return builder -> {
        if (!properties.json().includeNulls()) {
          builder.changeDefaultPropertyInclusion(
              i -> i.withValueInclusion(JsonInclude.Include.NON_NULL));
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  static class ServletConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestMetadataFilter axizRequestMetadataFilter(
        RequestMetadataResolver resolver, ObjectProvider<ProblemFactory> problems) {
      return new RequestMetadataFilter(resolver, problems.getIfAvailable());
    }

    @Bean
    RequestBodyLimitFilter axizRequestBodyLimitFilter(AxizWebProperties properties) {
      return new RequestBodyLimitFilter(new RequestBodyLimits(properties));
    }

    @Bean
    AxizWebMvcConfigurer axizWebMvcConfigurer(
        AxizWebProperties properties, PageRequestParser parser, Environment environment) {
      CorsPolicy.validate(properties.cors(), environment.getProperty("axiz.config.environment"));
      return new AxizWebMvcConfigurer(properties, parser);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  static class ReactiveConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestMetadataWebFilter axizRequestMetadataWebFilter(
        RequestMetadataResolver resolver, ObjectProvider<ProblemFactory> problems) {
      return new RequestMetadataWebFilter(resolver, problems.getIfAvailable());
    }

    @Bean
    RequestBodyLimitWebFilter axizRequestBodyLimitWebFilter(AxizWebProperties properties) {
      return new RequestBodyLimitWebFilter(new RequestBodyLimits(properties));
    }

    @Bean
    AxizWebFluxConfigurer axizWebFluxConfigurer(
        AxizWebProperties properties, PageRequestParser parser, Environment environment) {
      CorsPolicy.validate(properties.cors(), environment.getProperty("axiz.config.environment"));
      return new AxizWebFluxConfigurer(properties, parser);
    }
  }
}

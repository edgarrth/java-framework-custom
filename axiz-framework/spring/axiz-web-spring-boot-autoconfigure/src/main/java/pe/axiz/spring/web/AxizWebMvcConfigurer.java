package pe.axiz.spring.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Cableado de Spring MVC: parámetros de paginación, CORS y versionado nativo de la API. */
public class AxizWebMvcConfigurer implements WebMvcConfigurer {

  private final AxizWebProperties properties;
  private final PageRequestParser parser;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public AxizWebMvcConfigurer(AxizWebProperties properties, PageRequestParser parser) {
    this.properties = Objects.requireNonNull(properties);
    this.parser = Objects.requireNonNull(parser);
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new PageRequestArgumentResolver(parser));
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    AxizWebProperties.Cors c = properties.cors();
    if (c.enabled()) {
      CorsConfiguration configuration = new CorsConfiguration();
      configuration.setAllowedOrigins(c.allowedOrigins());
      configuration.setAllowedOriginPatterns(c.allowedOriginPatterns());
      configuration.setAllowedMethods(c.allowedMethods());
      configuration.setAllowedHeaders(c.allowedHeaders());
      configuration.setExposedHeaders(c.exposedHeaders());
      configuration.setAllowCredentials(c.allowCredentials());
      configuration.setMaxAge(c.maxAge().toSeconds());
      registry.addMapping(c.pathPattern()).combine(configuration);
    }
  }

  @Override
  public void configureApiVersioning(ApiVersionConfigurer configurer) {
    AxizWebProperties.ApiVersion v = properties.apiVersion();
    if (v.enabled()) {
      configurer
          .useRequestHeader(v.header())
          .setVersionRequired(v.required())
          .setDefaultVersion(v.effectiveDefaultVersion())
          .addSupportedVersions(v.supported().toArray(String[]::new));
    }
  }
}

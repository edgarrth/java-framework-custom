package pe.axiz.spring.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.ApiVersionConfigurer;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

/** WebFlux wiring: paging parameters, CORS and native API versioning. */
public class AxizWebFluxConfigurer implements WebFluxConfigurer {

  private final AxizWebProperties properties;
  private final PageRequestParser parser;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public AxizWebFluxConfigurer(AxizWebProperties properties, PageRequestParser parser) {
    this.properties = Objects.requireNonNull(properties);
    this.parser = Objects.requireNonNull(parser);
  }

  @Override
  public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
    long largest =
        properties.bodyLimits().stream()
            .mapToLong(rule -> rule.maxSize().toBytes())
            .max()
            .orElse(properties.maxRequestBody().toBytes());
    configurer
        .defaultCodecs()
        .maxInMemorySize(
            (int)
                Math.min(
                    Integer.MAX_VALUE, Math.max(largest, properties.maxRequestBody().toBytes())));
  }

  @Override
  public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
    configurer.addCustomResolver(new ReactivePageRequestArgumentResolver(parser));
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    AxizWebProperties.Cors c = properties.cors();
    if (c.enabled()) {
      registry
          .addMapping(c.pathPattern())
          .allowedOrigins(c.allowedOrigins().toArray(String[]::new))
          .allowedOriginPatterns(c.allowedOriginPatterns().toArray(String[]::new))
          .allowedMethods(c.allowedMethods().toArray(String[]::new))
          .allowedHeaders(c.allowedHeaders().toArray(String[]::new))
          .exposedHeaders(c.exposedHeaders().toArray(String[]::new))
          .allowCredentials(c.allowCredentials())
          .maxAge(c.maxAge().toSeconds());
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

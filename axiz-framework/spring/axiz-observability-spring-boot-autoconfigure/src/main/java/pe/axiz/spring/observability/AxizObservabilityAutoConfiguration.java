package pe.axiz.spring.observability;

import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.ErrorType;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AttributeSanitizer;
import pe.axiz.core.observability.LoggingStructuredEventEmitter;
import pe.axiz.core.observability.StructuredEventEmitter;

/**
 * Observaciones del framework: spans en los puntos de la arquitectura con atributos de negocio,
 * eventos estructurados, atributos seguros frente a PII, contexto de la solicitud en los spans y
 * sin observaciones para las sondas. Se ejecuta después de las autoconfiguraciones de observación y
 * métricas de Boot, de modo que el registro ya existe.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration"
    })
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBooleanProperty(name = "axiz.observability.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizObservabilityProperties.class)
public class AxizObservabilityAutoConfiguration {

  /** Los errores de negocio, de validación y de seguridad son rechazos, no errores. */
  public static final Function<Throwable, Boolean> REJECTIONS =
      t ->
          t instanceof AxizException a
              && (a.type() == ErrorType.BUSINESS
                  || a.type() == ErrorType.VALIDATION
                  || a.type() == ErrorType.SECURITY);

  @Bean
  @ConditionalOnMissingBean
  AttributeSanitizer axizAttributeSanitizer(AxizObservabilityProperties properties) {
    return new AttributeSanitizer(properties.deniedAttributes());
  }

  @Bean
  @ConditionalOnMissingBean
  ArchitectureObservation axizArchitectureObservation(
      ObjectProvider<ObservationRegistry> registry, AttributeSanitizer sanitizer) {
    return new ArchitectureObservation(
        registry.getIfAvailable(() -> ObservationRegistry.NOOP), REJECTIONS, sanitizer);
  }

  @Bean
  @ConditionalOnMissingBean
  StructuredEventEmitter axizStructuredEventEmitter(
      ObjectProvider<ObservationRegistry> registry, AttributeSanitizer sanitizer) {
    return new LoggingStructuredEventEmitter(
        registry.getIfAvailable(() -> ObservationRegistry.NOOP), sanitizer);
  }

  /** Personalizaciones del registro, aplicadas solo donde Boot gestiona el registro. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name =
          "org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer")
  static class RegistryConfiguration {

    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> axizObservationRegistryCustomizer(
        AxizObservabilityProperties properties) {
      return registry -> {
        if (properties.contextAttributes()) {
          registry.observationConfig().observationFilter(new RequestContextObservationFilter());
        }
        if (!properties.ignoredPaths().isEmpty()) {
          registry
              .observationConfig()
              .observationPredicate(
                  new IgnoredPathsObservationPredicate(properties.ignoredPaths()));
        }
      };
    }
  }
}

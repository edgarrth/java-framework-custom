package pe.axiz.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.autoconfigure.metrics.DataMetricsProperties;
import org.springframework.boot.data.autoconfigure.metrics.PropertiesAutoTimer;
import org.springframework.boot.data.metrics.DefaultRepositoryTagsProvider;
import org.springframework.boot.data.metrics.MetricsRepositoryMethodInvocationListener;
import org.springframework.boot.data.metrics.RepositoryTagsProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.util.function.SingletonSupplier;
import pe.axiz.core.metrics.EventDefinition;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.EventMetricsMeterFilter;
import pe.axiz.core.metrics.FailureClassifier;
import pe.axiz.core.metrics.Metered;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.metrics.TagPolicy;

/**
 * Métricas genéricas de eventos: la API {@link EventMetrics}, {@link Metered}, el filtro de
 * medidores que impone las etiquetas y los buckets declarados, y las etiquetas comunes {@code
 * service}, {@code env}, {@code version} y {@code domain}.
 */
@AutoConfiguration(
    beforeName =
        "org.springframework.boot.data.autoconfigure.metrics.DataRepositoryMetricsAutoConfiguration",
    afterName = {
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
    })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBooleanProperty(name = "axiz.metrics.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizMetricsProperties.class)
public class AxizMetricsAutoConfiguration {

  /** Claves de las etiquetas comunes añadidas a cada medidor. */
  public static final Set<String> COMMON_TAGS = Set.of("service", "env", "version", "domain");

  @Bean
  @ConditionalOnMissingBean
  static MetricsSettings axizMetricsSettings(AxizMetricsProperties p) {
    Map<String, TagPolicy> standard = new LinkedHashMap<>();
    p.standardTags().forEach((k, t) -> standard.put(k, policy(k, t)));
    Map<String, TagPolicy> custom = new LinkedHashMap<>();
    p.tags().forEach((k, t) -> custom.put(k, policy(k, t)));
    Map<String, EventDefinition> events = new LinkedHashMap<>();
    p.events()
        .forEach(
            (name, e) ->
                events.put(
                    name,
                    new EventDefinition(
                        name,
                        e.category(),
                        e.valueUnit(),
                        e.durationSlos(),
                        e.amountSlos(),
                        e.valueSlos())));
    return new MetricsSettings(
        p.maxEvents(),
        p.maxValuesPerTag(),
        p.channels(),
        standard,
        custom,
        events,
        p.percentileHistograms());
  }

  private static TagPolicy policy(String key, AxizMetricsProperties.Tag t) {
    return t.allowed().isEmpty()
        ? TagPolicy.bounded(key, t.max())
        : TagPolicy.allowed(key, t.allowed());
  }

  @Bean
  static MeterFilter axizEventMetricsMeterFilter(MetricsSettings settings) {
    return new EventMetricsMeterFilter(settings, COMMON_TAGS);
  }

  @Bean
  @ConditionalOnMissingBean
  FailureClassifier axizFailureClassifier() {
    return new AxizFailureClassifier();
  }

  @Bean
  @ConditionalOnMissingBean
  EventMetrics axizEventMetrics(
      MeterRegistry registry,
      MetricsSettings settings,
      ObjectProvider<FailureClassifier> classifiers) {
    return new EventMetrics(registry, settings, classifiers.orderedStream().toList());
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor axizMeteredAdvisor(ObjectProvider<EventMetrics> metrics) {
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Metered.class, true),
            new MeteredInterceptor(metrics::getObject));
    advisor.setOrder(10);
    return advisor;
  }

  /** Etiquetas comunes en cada registro gestionado por Boot. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = "org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer")
  static class CommonTagsConfiguration {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> axizCommonTags(
        Environment env, AxizMetricsProperties p, ObjectProvider<BuildProperties> build) {
      return registry -> registry.config().commonTags(commonTags(env, p, build.getIfAvailable()));
    }
  }

  static Tags commonTags(
      Environment env, AxizMetricsProperties p, @Nullable BuildProperties build) {
    String version =
        p.version() != null
            ? p.version()
            : build != null && build.getVersion() != null ? build.getVersion() : "unknown";
    return Tags.of(
        "service",
        env.getProperty("spring.application.name", "application"),
        "env",
        env.getProperty("axiz.config.environment", "default"),
        "version",
        version,
        "domain",
        p.domain() != null ? p.domain() : "none");
  }

  /**
   * Se resuelve el registro en el arranque; las finalizaciones de los repositorios nunca deben
   * inicializarlo de forma diferida.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(
      name = {
        "org.springframework.boot.data.metrics.MetricsRepositoryMethodInvocationListener",
        "org.springframework.data.repository.core.support.RepositoryMethodInvocationListener"
      })
  @EnableConfigurationProperties(DataMetricsProperties.class)
  static class ReactiveRepositoryMetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean(MetricsRepositoryMethodInvocationListener.class)
    MetricsRepositoryMethodInvocationListener axizReactiveRepositoryMetricsListener(
        MeterRegistry registry,
        ObjectProvider<RepositoryTagsProvider> tags,
        DataMetricsProperties properties) {
      var repository = properties.getRepository();
      return new MetricsRepositoryMethodInvocationListener(
          SingletonSupplier.of(registry),
          tags.getIfAvailable(DefaultRepositoryTagsProvider::new),
          repository.getMetricName(),
          new PropertiesAutoTimer(repository.getAutotime()));
    }
  }
}

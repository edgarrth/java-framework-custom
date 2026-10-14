package pe.axiz.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import pe.axiz.core.metrics.EventDefinition;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.EventMetricsMeterFilter;
import pe.axiz.core.metrics.FailureClassifier;
import pe.axiz.core.metrics.Metered;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.metrics.TagPolicy;

/**
 * Generic event metrics: the {@link EventMetrics} API, {@link Metered}, the meter filter that
 * enforces declared tags and buckets, and the common tags {@code service}, {@code env}, {@code
 * version} and {@code domain}.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
    })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBooleanProperty(name = "axiz.metrics.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizMetricsProperties.class)
public class AxizMetricsAutoConfiguration {

  /** Common tag keys added to every meter. */
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

  /** Common tags on every registry managed by Boot. */
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
      Environment env,
      AxizMetricsProperties p,
      @org.jspecify.annotations.Nullable BuildProperties build) {
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
}

package pe.axiz.spring.metrics;

import io.micrometer.registry.otlp.ExemplarContextProvider;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;
import org.springframework.context.annotation.Bean;

/** OTLP exemplars never resolve Spring beans from client I/O or metric recording callbacks. */
@AutoConfiguration(
    afterName =
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
    beforeName =
        "org.springframework.boot.micrometer.tracing.autoconfigure.otlp.OtlpExemplarsAutoConfiguration")
@ConditionalOnClass({ExemplarContextProvider.class, Tracer.class, TracingProperties.class})
@ConditionalOnBean(Tracer.class)
@org.springframework.context.annotation.Conditional(
    AxizOtlpExemplarsAutoConfiguration.ExemplarsEnabled.class)
@EnableConfigurationProperties(TracingProperties.class)
public class AxizOtlpExemplarsAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(ExemplarContextProvider.class)
  ExemplarContextProvider axizOtlpExemplarContextProvider(
      ObjectProvider<Tracer> tracers, TracingProperties properties) {
    return new StartupExemplarContextProvider(tracers, properties.getExemplars().getInclude());
  }

  static class ExemplarsEnabled
      extends org.springframework.boot.autoconfigure.condition.SpringBootCondition {
    @Override
    public org.springframework.boot.autoconfigure.condition.ConditionOutcome getMatchOutcome(
        org.springframework.context.annotation.ConditionContext context,
        org.springframework.core.type.AnnotatedTypeMetadata metadata) {
      var include =
          org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment())
              .bind(
                  "management.tracing.exemplars.include", TracingProperties.Exemplars.Include.class)
              .orElse(TracingProperties.Exemplars.Include.SAMPLED_TRACES);
      return include == TracingProperties.Exemplars.Include.NONE
          ? org.springframework.boot.autoconfigure.condition.ConditionOutcome.noMatch(
              "OTLP exemplars disabled")
          : org.springframework.boot.autoconfigure.condition.ConditionOutcome.match(
              "OTLP exemplars enabled");
    }
  }
}

package pe.axiz.spring.metrics;

import io.micrometer.registry.otlp.ExemplarContextProvider;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** OTLP exemplars never resolve Spring beans from client I/O or metric recording callbacks. */
@AutoConfiguration(
    afterName =
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
    beforeName =
        "org.springframework.boot.micrometer.tracing.autoconfigure.otlp.OtlpExemplarsAutoConfiguration")
@ConditionalOnClass({ExemplarContextProvider.class, Tracer.class, TracingProperties.class})
@ConditionalOnBean(Tracer.class)
@Conditional(AxizOtlpExemplarsAutoConfiguration.ExemplarsEnabled.class)
@EnableConfigurationProperties(TracingProperties.class)
public class AxizOtlpExemplarsAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(ExemplarContextProvider.class)
  ExemplarContextProvider axizOtlpExemplarContextProvider(
      ObjectProvider<Tracer> tracers, TracingProperties properties) {
    return new StartupExemplarContextProvider(tracers, properties.getExemplars().getInclude());
  }

  static class ExemplarsEnabled extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      var include =
          Binder.get(context.getEnvironment())
              .bind(
                  "management.tracing.exemplars.include", TracingProperties.Exemplars.Include.class)
              .orElse(TracingProperties.Exemplars.Include.SAMPLED_TRACES);
      return include == TracingProperties.Exemplars.Include.NONE
          ? ConditionOutcome.noMatch("OTLP exemplars disabled")
          : ConditionOutcome.match("OTLP exemplars enabled");
    }
  }
}

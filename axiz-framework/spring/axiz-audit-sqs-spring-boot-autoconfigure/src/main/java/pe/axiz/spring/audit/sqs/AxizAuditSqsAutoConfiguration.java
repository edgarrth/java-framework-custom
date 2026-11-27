package pe.axiz.spring.audit.sqs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.audit.sqs.SqsAsyncAuditPublisher;
import pe.axiz.adapter.audit.sqs.SqsAuditPublisher;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.spring.audit.AsyncAuditSink;
import pe.axiz.spring.audit.AsyncStrictAuditSink;
import pe.axiz.spring.audit.AuditSink;
import pe.axiz.spring.audit.StrictAuditSink;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Los clientes SQS dedicados a auditoría usan la identidad opcional de auditoría, servicio o global
 * y las convenciones de transporte AWS del framework. Su identidad con nombre y no predeterminada
 * permite una cuenta de auditoría separada sin convertirse en el cliente de mensajería. Las
 * aplicaciones pueden reemplazarlos mediante el nombre explícito del bean de auditoría.
 */
@AutoConfiguration(
    beforeName = {
      "pe.axiz.spring.audit.AxizAuditAutoConfiguration",
      "pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration"
    })
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.audit.sqs.SqsAuditPublisher",
      "software.amazon.awssdk.services.sqs.SqsClient"
    })
@ConditionalOnConfiguredProperty("axiz.audit.sqs.queue-url")
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizAuditSqsProperties.class, AxizAwsCompletionProperties.class})
public class AxizAuditSqsAutoConfiguration {
  /** Enlaza y valida la configuración del destino antes de construir los recursos de transporte. */
  public AxizAuditSqsAutoConfiguration(AxizAuditSqsProperties properties) {
    Objects.requireNonNull(properties);
    super();
  }

  @Bean(destroyMethod = "close", defaultCandidate = false)
  @ConditionalOnMissingBean(name = "axizAuditSqsClient")
  @ConditionalOnImperativeApplication
  SqsClient axizAuditSqsClient(
      Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
    return AwsObservability.configured(
        identity.configure(SqsClient.builder(), AwsService.SQS, AwsCapability.AUDIT),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "sqs");
  }

  @Bean
  @ConditionalOnImperativeApplication
  AuditSink axizSqsAuditSink(
      @Qualifier("axizAuditSqsClient") SqsClient sqs, AxizAuditSqsProperties properties) {
    return new AuditSink(
        "sqs", new SqsAuditPublisher(sqs, Objects.requireNonNull(properties.queueUrl())));
  }

  @Bean
  @ConditionalOnImperativeApplication
  StrictAuditSink axizSqsStrictAuditSink(@Qualifier("axizSqsAuditSink") AuditSink sink) {
    return new StrictAuditSink("sqs", sink.publisher());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class NativeConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(name = "axizAuditSqsAsyncClient")
    static class ClientConfiguration {
      @Bean(destroyMethod = "close")
      AsyncAwsClients axizAuditAsyncAwsResources(
          AxizAwsCompletionProperties completion,
          ObjectProvider<MeterRegistry> registries,
          AwsIdentity identity,
          AxizAwsAsyncTransportProperties transport) {
        return AwsAsyncResources.create(
            "axizAuditAsyncAwsResources",
            completion.workers(),
            completion.queueCapacity(),
            registries.getIfAvailable(),
            identity,
            AwsService.SQS,
            AwsCapability.AUDIT,
            transport.toSettings());
      }

      @Bean(destroyMethod = "close", defaultCandidate = false)
      SqsAsyncClient axizAuditSqsAsyncClient(
          @Qualifier("axizAuditAsyncAwsResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations) {
        return AwsObservability.configured(
            clients,
            SqsAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "sqs");
      }
    }

    @Bean
    AsyncStrictAuditSink axizSqsAsyncStrictAuditSink(
        @Qualifier("axizSqsAsyncAuditSink") AsyncAuditSink sink) {
      return new AsyncStrictAuditSink("sqs", sink.publisher());
    }

    @Bean
    AsyncAuditSink axizSqsAsyncAuditSink(
        @Qualifier("axizAuditSqsAsyncClient") SqsAsyncClient sqs,
        AxizAuditSqsProperties properties) {
      return new AsyncAuditSink(
          "sqs", new SqsAsyncAuditPublisher(sqs, Objects.requireNonNull(properties.queueUrl())));
    }
  }
}

package pe.axiz.spring.audit;

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
import pe.axiz.spring.foundation.AxizAwsCompletionProperties;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.foundation.aws.AwsAsyncResources;
import pe.axiz.spring.foundation.aws.AwsCapability;
import pe.axiz.spring.foundation.aws.AwsIdentity;
import pe.axiz.spring.foundation.aws.AwsService;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Dedicated SQS audit clients use optional audit/service/global identity identity and framework AWS
 * transport conventions. Their named, non-default identity allow a separate audit account without
 * becoming messaging's client. Applications may replace them by the explicit audit bean name.
 */
@AutoConfiguration
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.audit.sqs.SqsAuditPublisher",
      "software.amazon.awssdk.services.sqs.SqsClient"
    })
@ConditionalOnConfiguredProperty("axiz.audit.sqs.queue-url")
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizAuditProperties.class, AxizAwsCompletionProperties.class})
public class AxizAuditSqsAutoConfiguration {

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
      @Qualifier("axizAuditSqsClient") SqsClient sqs, AxizAuditProperties properties) {
    return new AuditSink(
        "sqs", new SqsAuditPublisher(sqs, Objects.requireNonNull(properties.sqs().queueUrl())));
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
          AwsIdentity identity) {
        return AwsAsyncResources.create(
            "axizAuditAsyncAwsResources",
            completion.workers(),
            completion.queueCapacity(),
            registries.getIfAvailable(),
            identity,
            AwsService.SQS,
            AwsCapability.AUDIT);
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
    AsyncAuditSink axizSqsAsyncAuditSink(
        @Qualifier("axizAuditSqsAsyncClient") SqsAsyncClient sqs, AxizAuditProperties properties) {
      return new AsyncAuditSink(
          "sqs",
          new SqsAsyncAuditPublisher(sqs, Objects.requireNonNull(properties.sqs().queueUrl())));
    }
  }
}

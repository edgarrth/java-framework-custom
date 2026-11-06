package pe.axiz.spring.audit;

import pe.axiz.spring.foundation.AxizAwsCompletionProperties;

import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.audit.sqs.SqsAsyncAuditPublisher;
import pe.axiz.adapter.audit.sqs.SqsAuditPublisher;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SQS sink for audit events, active when {@code axiz.audit.sqs.queue-url} is set. Credentials come
 * from the SDK default chain (IRSA or EKS Pod Identity); without an {@link SqsClient} bean the
 * client is built like the messaging one ({@code axiz.aws.region}, {@code axiz.aws.sqs.endpoint} or
 * {@code axiz.aws.endpoint}, observed).
 */
@AutoConfiguration
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.audit.sqs.SqsAuditPublisher",
      "software.amazon.awssdk.services.sqs.SqsClient"
    })
@ConditionalOnProperty("axiz.audit.sqs.queue-url")
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizAuditProperties.class, AxizAwsCompletionProperties.class})
public class AxizAuditSqsAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  SqsClient axizSqsClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.configured(
        SqsClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "sqs");
  }

  @Bean
  @ConditionalOnImperativeApplication
  AuditSink axizSqsAuditSink(SqsClient sqs, AxizAuditProperties properties) {
    return new AuditSink(
        "sqs", new SqsAuditPublisher(sqs, Objects.requireNonNull(properties.sqs().queueUrl())));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class NativeConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(SqsAsyncClient.class)
    static class ClientConfiguration {
      @Bean(destroyMethod = "close")
      AsyncAwsClients axizAuditAsyncAwsResources(AxizAwsCompletionProperties completion) {
      return new AsyncAwsClients("axizAuditAsyncAwsResources", completion.workers(), completion.queueCapacity());
      }

      @Bean(destroyMethod = "close")
      SqsAsyncClient axizAuditSqsAsyncClient(
          @Qualifier("axizAuditAsyncAwsResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations) {
        return clients.configured(
            SqsAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "sqs");
      }
    }

    @Bean
    AsyncAuditSink axizSqsAsyncAuditSink(SqsAsyncClient sqs, AxizAuditProperties properties) {
      return new AsyncAuditSink(
          "sqs",
          new SqsAsyncAuditPublisher(sqs, Objects.requireNonNull(properties.sqs().queueUrl())));
    }
  }
}

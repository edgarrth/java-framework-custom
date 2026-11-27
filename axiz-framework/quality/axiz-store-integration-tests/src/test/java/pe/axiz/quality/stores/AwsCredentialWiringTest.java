package pe.axiz.quality.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.spring.audit.sqs.AxizAuditSqsAutoConfiguration;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentityFor;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.messaging.sqs.AxizMessagingSqsAutoConfiguration;
import pe.axiz.spring.messaging.sqs.AxizNativeMessagingSqsAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;

class AwsCredentialWiringTest {
  private static final String[] SETTINGS = {
    "axiz.audit.sqs.queue-url=https://localhost:4566/000000000000/audit",
    "axiz.messaging.destinations.orders=https://localhost:4566/000000000000/orders",
    "axiz.aws.region=us-east-1",
    "axiz.aws.endpoint=https://127.0.0.1:1"
  };

  @Test
  void auditAndMessagingHaveIndependentSyncIdentities() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withUserConfiguration(Identities.class)
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditSqsAutoConfiguration.class, AxizMessagingSqsAutoConfiguration.class))
        .withPropertyValues(SETTINGS)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var audit = ctx.getBean("axizAuditSqsClient", SqsClient.class);
              var messaging = ctx.getBean("axizSqsClient", SqsClient.class);
              assertNotSame(audit, messaging);
              assertEquals(
                  "audit",
                  audit
                      .serviceClientConfiguration()
                      .credentialsProvider()
                      .resolveIdentity()
                      .join()
                      .accessKeyId());
              assertEquals(
                  "sqs",
                  messaging
                      .serviceClientConfiguration()
                      .credentialsProvider()
                      .resolveIdentity()
                      .join()
                      .accessKeyId());
            });
  }

  @Test
  void auditAndMessagingHaveIndependentWarmedAsyncIdentities() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withUserConfiguration(Identities.class)
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditSqsAutoConfiguration.class, AxizNativeMessagingSqsAutoConfiguration.class))
        .withPropertyValues(SETTINGS)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var audit = ctx.getBean("axizAuditSqsAsyncClient", SqsAsyncClient.class);
              var messaging = ctx.getBean("axizMessagingSqsAsyncClient", SqsAsyncClient.class);
              assertNotSame(audit, messaging);
              assertEquals(
                  "audit",
                  audit
                      .serviceClientConfiguration()
                      .credentialsProvider()
                      .resolveIdentity()
                      .join()
                      .accessKeyId());
              assertEquals(
                  "sqs",
                  messaging
                      .serviceClientConfiguration()
                      .credentialsProvider()
                      .resolveIdentity()
                      .join()
                      .accessKeyId());
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class Identities {
    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider audit() {
      return () -> AwsBasicCredentials.create("audit", "fixture");
    }

    @Bean
    @AwsIdentityFor(service = AwsService.SQS)
    AwsCredentialsProvider messaging() {
      return () -> AwsBasicCredentials.create("sqs", "fixture");
    }
  }
}

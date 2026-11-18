package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.spring.audit.kafka.AxizAuditKafkaAutoConfiguration;
import pe.axiz.spring.audit.sqs.AxizAuditSqsAutoConfiguration;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;

class AuditProviderValidationTest {
  @Test
  void explicitBlankTransportSettingsFailBeforeAnyPublication() {
    for (String key : List.of("axiz.audit.sqs.queue-url", "axiz.audit.kafka.topic")) {
      for (String value : List.of("", " ", "${AXIZ_MISSING_AUDIT_DESTINATION}")) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAuditAutoConfiguration.class,
                    key.contains("kafka")
                        ? AxizAuditKafkaAutoConfiguration.class
                        : AxizAuditSqsAutoConfiguration.class))
            .withPropertyValues("axiz.audit.mode=ASYNC", key + "=" + value)
            .run(
                ctx ->
                    assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("must not be blank"));
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAuditAutoConfiguration.class,
                    AxizNativeAuditAutoConfiguration.class,
                    key.contains("kafka")
                        ? AxizAuditKafkaAutoConfiguration.class
                        : AxizAuditSqsAutoConfiguration.class))
            .withPropertyValues("axiz.audit.mode=ASYNC", key + "=" + value)
            .run(
                ctx ->
                    assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("must not be blank"));
      }
    }
  }
}

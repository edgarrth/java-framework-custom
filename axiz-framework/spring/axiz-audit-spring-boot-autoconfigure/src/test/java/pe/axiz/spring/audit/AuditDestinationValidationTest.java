package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;

class AuditDestinationValidationTest {
  @Test
  void dottedScalarMapKeysRespectBothSpellingsAndSourcePrecedence() {
    for (String spelling : List.of(".audit.v2", "[audit.v2]")) {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
          .withPropertyValues(
              "axiz.audit.mode=DURABLE",
              "axiz.audit.durable.destination=audit.v2",
              "axiz.messaging.destinations.audit.v2=kafka:lower")
          .withInitializer(
              context ->
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .addFirst(
                          new MapPropertySource(
                              "higher",
                              Map.of(
                                  "axiz.messaging.destinations" + spelling,
                                  "${AXIZ_MISSING_DOTTED_ROUTE}"))))
          .withBean(OutboxWriter.class, () -> mock(OutboxWriter.class))
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
          .withBean(OutboxRelay.class, () -> mock(OutboxRelay.class))
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .hasStackTraceContaining("axiz.messaging.destinations[audit.v2]")
                      .hasStackTraceContaining("unresolved variables: [AXIZ_MISSING_DOTTED_ROUTE]")
                      .hasStackTraceContaining("resolves to '${AXIZ_MISSING_DOTTED_ROUTE}'"));
    }
  }

  @Test
  void unsupportedRoutesIdentifyTheResolvedDestinationOrMissingVariableWithoutUriCredentials() {
    for (String destination :
        List.of(
            "${AXIZ_REQUIRED_AUDIT_ROUTE}",
            "https://user:secret@example.test/audit?token=hidden")) {
      var relay = mock(OutboxRelay.class);
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
          .withPropertyValues(
              "axiz.audit.mode=DURABLE", "axiz.messaging.destinations.audit=" + destination)
          .withBean(OutboxWriter.class, () -> mock(OutboxWriter.class))
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
          .withBean(OutboxRelay.class, () -> relay)
          .run(
              ctx -> {
                var failure =
                    assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("axiz.messaging.destinations.audit");
                if (destination.startsWith("${"))
                  failure.hasStackTraceContaining(
                      "unresolved variables: [AXIZ_REQUIRED_AUDIT_ROUTE]");
                else
                  failure
                      .hasStackTraceContaining("https://example.test/audit")
                      .hasStackTraceContaining("resolves to")
                      .hasMessageNotContaining("user:secret")
                      .hasMessageNotContaining("token=hidden");
              });
    }
  }

  @Test
  void explicitBlankTransportSettingsFailBeforeAnyPublication() {
    for (String key : List.of("axiz.audit.sqs.queue-url", "axiz.audit.kafka.topic")) {
      for (String value : List.of("", " ", "${AXIZ_MISSING_AUDIT_DESTINATION}")) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
            .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
            .withPropertyValues("axiz.audit.mode=ASYNC", key + "=" + value)
            .run(
                ctx ->
                    assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("must not be blank"));
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAuditAutoConfiguration.class, AxizNativeAuditAutoConfiguration.class))
            .withPropertyValues("axiz.audit.mode=ASYNC", key + "=" + value)
            .run(
                ctx ->
                    assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("must not be blank"));
      }
    }
  }

  @Test
  void durableChecksTheActualRelayInBothStacksButAllowsAWriterOnlyService() {
    for (boolean supported : List.of(false, true)) {
      var relay = mock(OutboxRelay.class);
      when(relay.supportsDestination("audit")).thenReturn(supported);
      var imperative =
          new ApplicationContextRunner()
              .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
              .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
              .withPropertyValues("axiz.audit.mode=DURABLE")
              .withBean(OutboxWriter.class, () -> mock(OutboxWriter.class))
              .withBean(
                  PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
      imperative.run(
          ctx ->
              assertThat(ctx.getStartupFailure())
                  .hasStackTraceContaining("requires an outbox relay publisher"));
      imperative
          .withPropertyValues("axiz.outbox.relay.enabled=false")
          .run(ctx -> assertThat(ctx).hasNotFailed());
      imperative
          .withBean(OutboxRelay.class, () -> relay)
          .run(
              ctx -> {
                if (supported) assertThat(ctx).hasNotFailed();
                else
                  assertThat(ctx.getStartupFailure())
                      .hasStackTraceContaining("unsupported by the outbox relay");
              });
      var reactive =
          new ReactiveWebApplicationContextRunner()
              .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
              .withConfiguration(
                  AutoConfigurations.of(
                      AxizAuditAutoConfiguration.class, AxizNativeAuditAutoConfiguration.class))
              .withPropertyValues("axiz.audit.mode=DURABLE")
              .withBean(ReactiveOutboxWriter.class, () -> mock(ReactiveOutboxWriter.class))
              .withBean(
                  ReactiveTransactionManager.class, () -> mock(ReactiveTransactionManager.class));
      reactive.run(
          ctx ->
              assertThat(ctx.getStartupFailure())
                  .hasStackTraceContaining("requires an outbox relay publisher"));
      reactive
          .withPropertyValues("axiz.outbox.relay.enabled=false")
          .run(ctx -> assertThat(ctx).hasNotFailed());
      reactive
          .withBean(OutboxRelay.class, () -> relay)
          .run(
              ctx -> {
                if (supported) assertThat(ctx).hasNotFailed();
                else
                  assertThat(ctx.getStartupFailure())
                      .hasStackTraceContaining("unsupported by the outbox relay");
              });
    }
  }
}

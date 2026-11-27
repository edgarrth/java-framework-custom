package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditPublisherDecorator;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.ConfirmedAuditPublisher;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

class AuditIntegrityWiringTest {
  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.mode=SYNC");
  }

  @Test
  void hmacKeyIsLoadedOnceClosedAndUsedOnlyAsAnOwnedSnapshot() {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 27);
    var secret = SecretValue.of(Base64.getEncoder().encodeToString(key));
    var resolves = new AtomicInteger();
    List<AuditEvent> received = new CopyOnWriteArrayList<>();
    runner()
        .withPropertyValues(
            "axiz.audit.integrity.mode=HMAC",
            "axiz.audit.integrity.hmac-secret=audit/key",
            "axiz.audit.integrity.hmac-key-id=key-1")
        .withBean(
            SecretResolver.class,
            () ->
                name -> {
                  resolves.incrementAndGet();
                  return Optional.of(secret);
                })
        .withBean(AuditSink.class, () -> new AuditSink("memory", received::add))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              for (int i = 0; i < 3; i++)
                ctx.getBean(AuditTrail.class).record("change", "account").success();
              assertEquals(1, resolves.get());
              assertThrows(IllegalStateException.class, secret::revealChars);
              assertEquals(
                  AuditChainVerifier.IntegrityStatus.INTACT,
                  AuditChainVerifier.verify(received, key, null).integrity());
              ctx.getBean(AuditIntegritySupport.class).close();
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      ctx.getBean(AuditPublisher.class)
                          .publish(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS)));
            });
  }

  @Test
  void noneIsLocalOnlyAndHmacRequiresAnExplicitSeparateKey() {
    for (String unkeyed : new String[] {"NONE", "HASH"})
      runner()
          .withPropertyValues(
              "axiz.audit.integrity.mode=" + unkeyed, "axiz.config.environment=prod")
          .run(
              ctx ->
                  assertThat(ctx.getStartupFailure())
                      .hasStackTraceContaining(
                          "Audit integrity " + unkeyed + " is restricted to local/test/ci"));
    runner()
        .withPropertyValues("axiz.config.environment=prod")
        .run(ctx -> assertThat(ctx).hasFailed()); // HASH es solo el valor por defecto de desarrollo
    runner()
        .withPropertyValues("axiz.audit.integrity.mode=NONE", "axiz.config.environment=test")
        .run(ctx -> assertThat(ctx).hasNotFailed());
    runner()
        .withPropertyValues(
            "axiz.audit.integrity.mode=HMAC", "axiz.audit.integrity.hmac-secret=key")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void integrityMetricsExposeSanitizationAndAnOperationalSequenceThroughDecorators() {
    List<AuditEvent> received = new CopyOnWriteArrayList<>();
    runner()
        .withBean(AuditSink.class, () -> new AuditSink("memory", received::add))
        .run(
            ctx -> {
              var registry = new SimpleMeterRegistry();
              ctx.getBean(MeterBinder.class).bindTo(registry);
              ctx.getBean(AuditTrail.class)
                  .record("change", "account")
                  .detail("large", BigInteger.ONE.shiftLeft(4096))
                  .success();
              assertThat(received.getFirst().detailsSanitized()).isPositive();
              assertEquals(
                  received.getFirst().detailsSanitized(),
                  registry.get("axiz.audit.details.sanitized").functionCounter().count());
              assertEquals(
                  1.0,
                  registry
                      .get("axiz.audit.chain.sequence")
                      .tag("lane", "delivery")
                      .gauge()
                      .value());
            });
  }

  @Test
  void decoratorLookupIsBoundedAndKeepsTheConfirmationMetricsVisible() {
    runner()
        .run(
            ctx ->
                assertThat(
                        AuditPublisherLookup.find(
                            ctx.getBean(AuditPublisher.class), ConfirmedAuditPublisher.class))
                    .isNotNull());
    class Cycle implements AuditPublisher, AuditPublisherDecorator {
      @Override
      public AuditPublisher delegate() {
        return this;
      }

      @Override
      public void publish(AuditEvent event) {}
    }
    assertThat(AuditPublisherLookup.find(new Cycle(), ConfirmedAuditPublisher.class)).isNull();
  }
}

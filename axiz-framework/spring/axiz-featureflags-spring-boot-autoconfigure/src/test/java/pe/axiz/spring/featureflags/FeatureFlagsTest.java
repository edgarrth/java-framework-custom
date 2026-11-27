package pe.axiz.spring.featureflags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.featureflags.FeatureFlags;

class FeatureFlagsTest {

  @Test
  void flagsByChannelFromConfiguration() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizFeatureFlagsAutoConfiguration.class))
        .withPropertyValues(
            "axiz.feature-flags.flags.qr-payments.enabled=true",
            "axiz.feature-flags.flags.qr-payments.channels=mobile",
            "axiz.feature-flags.flags.new-fees.enabled=true",
            "axiz.feature-flags.flags.new-fees.variant=v2")
        .run(
            ctx -> {
              FeatureFlags flags = ctx.getBean(FeatureFlags.class);
              try (RequestContext.Scope s =
                  RequestContext.open(
                      RequestMetadata.builder("corr-12345678", "req-12345678")
                          .channel("mobile")
                          .build())) {
                assertTrue(flags.isEnabled("qr-payments", FlagContexts.current()));
              }
              try (RequestContext.Scope s =
                  RequestContext.open(
                      RequestMetadata.builder("corr-12345678", "req-12345678")
                          .channel("web")
                          .build())) {
                assertFalse(flags.isEnabled("qr-payments", FlagContexts.current()));
              }
              assertEquals("v2", flags.variant("new-fees", FlagContexts.current(), "v1"));
              assertFalse(flags.isEnabled("unknown", FlagContexts.current()));
            });
  }
}

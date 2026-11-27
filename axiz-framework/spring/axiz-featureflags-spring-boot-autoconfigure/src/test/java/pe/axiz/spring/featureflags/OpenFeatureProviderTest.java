package pe.axiz.spring.featureflags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.featureflags.FeatureFlags;
import pe.axiz.core.featureflags.FlagDefinition;
import pe.axiz.core.featureflags.MapFlagProvider;

/**
 * Los invocantes de OpenFeature obtienen los flags configurados, y su propio valor por defecto para
 * los desconocidos.
 */
class OpenFeatureProviderTest {

  private final AxizOpenFeatureProvider provider =
      new AxizOpenFeatureProvider(
          new FeatureFlags(
              new MapFlagProvider(
                  List.of(
                      FlagDefinition.on("qr-payments"),
                      FlagDefinition.off("legacy-fees"),
                      new FlagDefinition(
                          "mobile-only", true, 100, Set.of("mobile"), Set.of(), Map.of(), null),
                      new FlagDefinition("fees", true, 100, Set.of(), Set.of(), Map.of(), "v2")))));

  @Test
  void configuredBooleanFlagsAreEvaluated() {
    assertEquals("axiz", provider.getMetadata().getName());
    ProviderEvaluation<Boolean> on =
        provider.getBooleanEvaluation("qr-payments", false, new ImmutableContext());
    assertTrue(on.getValue());
    assertEquals(Reason.TARGETING_MATCH.name(), on.getReason());
    assertNull(on.getErrorCode());
    assertFalse(
        provider.getBooleanEvaluation("legacy-fees", true, new ImmutableContext()).getValue());
  }

  @Test
  void anUnknownFlagReturnsTheCallersDefaultAndReportsIt() {
    ProviderEvaluation<Boolean> missing =
        provider.getBooleanEvaluation("qr-paymnets", true, new ImmutableContext());
    assertTrue(missing.getValue());
    assertEquals(ErrorCode.FLAG_NOT_FOUND, missing.getErrorCode());
    assertEquals(Reason.ERROR.name(), missing.getReason());

    ProviderEvaluation<String> variant =
        provider.getStringEvaluation("unknown", "v1", new ImmutableContext());
    assertEquals("v1", variant.getValue());
    assertEquals(ErrorCode.FLAG_NOT_FOUND, variant.getErrorCode());
  }

  @Test
  void contextAttributesOverrideTheRequest() {
    RequestMetadata web =
        RequestMetadata.builder("corr-12345678", "req-12345678").channel("web").build();
    try (RequestContext.Scope ignored = RequestContext.open(web)) {
      assertFalse(
          provider.getBooleanEvaluation("mobile-only", false, new ImmutableContext()).getValue());
      assertTrue(
          provider
              .getBooleanEvaluation(
                  "mobile-only",
                  false,
                  new ImmutableContext("user-1", Map.of("channel", new Value("mobile"))))
              .getValue());
    }
  }

  @Test
  void variantsAndUnsupportedTypes() {
    ProviderEvaluation<String> fees =
        provider.getStringEvaluation("fees", "v1", new ImmutableContext());
    assertEquals("v2", fees.getValue());
    assertEquals("v2", fees.getVariant());
    ProviderEvaluation<Integer> number =
        provider.getIntegerEvaluation("fees", 7, new ImmutableContext());
    assertEquals(7, number.getValue());
    assertEquals(Reason.DEFAULT.name(), number.getReason());
    assertEquals(1.5, provider.getDoubleEvaluation("fees", 1.5, new ImmutableContext()).getValue());
    Value fallback = new Value("x");
    assertSame(
        fallback,
        provider.getObjectEvaluation("fees", fallback, new ImmutableContext()).getValue());
  }

  @Test
  void theClientIsWiredToTheConfiguredFlags() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizFeatureFlagsAutoConfiguration.class, AxizOpenFeatureAutoConfiguration.class))
        .withPropertyValues("axiz.feature-flags.flags.qr-payments.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(Client.class);
              Client client = ctx.getBean(Client.class);
              assertTrue(client.getBooleanValue("qr-payments", false));
              assertTrue(client.getBooleanValue("not-configured", true));
            });
  }
}

package pe.axiz.spring.tokenization.vault.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import pe.axiz.core.api.HttpPayloadPolicy;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties.Auth;
import pe.axiz.spring.tokenization.vault.VerifiedTokenCaller;

class VaultHttpConfigurationTest {
  private static final AutoConfigurations CONFIG =
      AutoConfigurations.of(AxizTokenizationVaultHttpAutoConfiguration.class);

  private static VerifiedTokenCaller callers() {
    return new VerifiedTokenCaller(
        new Auth("tenant", null, "client_id", "domains", "SCOPE_tokens:"));
  }

  @Test
  void servletSelectsItsOwnEngineAndGuardsAndRejectsUnsafeProductionOverrides() {
    var base =
        new WebApplicationContextRunner()
            .withConfiguration(CONFIG)
            .withBean(VaultOperations.class, () -> mock(VaultOperations.class))
            .withBean(VerifiedTokenCaller.class, VaultHttpConfigurationTest::callers);
    base.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(ServletVaultController.class)
                .hasSingleBean(ServletVaultGuard.class)
                .hasSingleBean(HttpPayloadPolicy.class)
                .doesNotHaveBean(ReactiveVaultController.class));
    base.withPropertyValues("axiz.tokenization.vault.http.allow-insecure=true")
        .run(context -> assertThat(context).hasFailed());
    base.withPropertyValues("spring.mvc.log-request-details=true")
        .run(context -> assertThat(context).hasFailed());
    base.withPropertyValues("spring.codec.log-request-details=true")
        .run(context -> assertThat(context).hasFailed());
    base.withPropertyValues(
            "axiz.config.environment=test", "axiz.tokenization.vault.http.allow-insecure=true")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void reactiveCannotSubstituteAnImperativeEngineAndHasItsOwnGuard() {
    var base =
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(CONFIG)
            .withBean(VerifiedTokenCaller.class, VaultHttpConfigurationTest::callers);
    base.withBean(VaultOperations.class, () -> mock(VaultOperations.class))
        .run(context -> assertThat(context).hasFailed());
    base.withBean(AsyncVaultOperations.class, () -> mock(AsyncVaultOperations.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveVaultController.class)
                    .hasSingleBean(ReactiveVaultGuard.class)
                    .hasSingleBean(HttpPayloadPolicy.class)
                    .doesNotHaveBean(ServletVaultController.class));
  }
}

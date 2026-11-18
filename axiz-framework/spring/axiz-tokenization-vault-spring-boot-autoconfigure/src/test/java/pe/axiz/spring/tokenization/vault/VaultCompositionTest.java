package pe.axiz.spring.tokenization.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.tokenization.TextTokenProfiles;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.AsyncTokenReconciliationLog;
import pe.axiz.core.tokenization.vault.AsyncTokenVault;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.TokenAccessPolicy;
import pe.axiz.core.tokenization.vault.TokenBudget;
import pe.axiz.core.tokenization.vault.TokenReconciliationAlarm;
import pe.axiz.core.tokenization.vault.TokenReconciliationLog;
import pe.axiz.core.tokenization.vault.TokenVault;
import pe.axiz.core.tokenization.vault.VaultOperations;

class VaultCompositionTest {
  private static final String[] SETTINGS = {
    "spring.application.name=token-vault",
    "axiz.tokenization.vault.auth.tenant-claim=tenant",
    "axiz.tokenization.vault.auth.client-claim=client_id",
    "axiz.tokenization.vault.auth.domains-claim=token_domains",
    "axiz.tokenization.vault.auth.authority-prefix=SCOPE_tokens:",
    "axiz.tokenization.vault.types.DNI.profile=dni.pe",
    "axiz.tokenization.vault.types.DNI.version=1",
    "axiz.tokenization.vault.types.DNI.keyset=identity",
    "axiz.tokenization.vault.types.DNI.domains[0]=billing",
    "axiz.tokenization.vault.types.DNI.purposes[0]=customer-check",
    "axiz.tokenization.vault.types.DNI.retention=30d"
  };

  private static BlindIndex indexes() {
    return new BlindIndex(Map.of("index-1", new byte[32]), "index-1");
  }

  private static TokenBudget budget(boolean distributed) {
    var budget = mock(TokenBudget.class);
    when(budget.distributed()).thenReturn(distributed);
    return budget;
  }

  private static ApplicationContextRunner imperative() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizTokenizationVaultAutoConfiguration.class))
        .withPropertyValues(SETTINGS)
        .withBean(TokenAccessPolicy.class, () -> mock(TokenAccessPolicy.class))
        .withBean(BlindIndex.class, VaultCompositionTest::indexes)
        .withBean(TokenVault.class, () -> mock(TokenVault.class))
        .withBean(EnvelopeEncryptor.class, () -> mock(EnvelopeEncryptor.class))
        .withBean(TokenReconciliationLog.class, () -> mock(TokenReconciliationLog.class))
        .withBean(TokenReconciliationAlarm.class, () -> mock(TokenReconciliationAlarm.class));
  }

  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizTokenizationVaultAutoConfiguration.class))
        .withPropertyValues(SETTINGS)
        .withBean(TokenAccessPolicy.class, () -> mock(TokenAccessPolicy.class))
        .withBean(BlindIndex.class, VaultCompositionTest::indexes)
        .withBean(AsyncTokenVault.class, () -> mock(AsyncTokenVault.class))
        .withBean(AsyncEnvelopeEncryptor.class, () -> mock(AsyncEnvelopeEncryptor.class))
        .withBean(AsyncTokenReconciliationLog.class, () -> mock(AsyncTokenReconciliationLog.class))
        .withBean(TokenReconciliationAlarm.class, () -> mock(TokenReconciliationAlarm.class))
        .withBean(
            AsyncTokenBudget.class,
            () -> {
              var budget = mock(AsyncTokenBudget.class);
              when(budget.distributed()).thenReturn(true);
              return budget;
            });
  }

  @Test
  void imperativeCompositionRequiresDistributedBudgetAndConfirmedAudit() {
    imperative()
        .withBean(TokenBudget.class, () -> budget(true))
        .withBean(StrictAuditPublisher.class, () -> mock(StrictAuditPublisher.class))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(VaultOperations.class)
                  .hasSingleBean(VerifiedTokenCaller.class)
                  .doesNotHaveBean(AsyncVaultOperations.class);
            });
    imperative()
        .withBean(TokenBudget.class, () -> budget(false))
        .withBean(StrictAuditPublisher.class, () -> mock(StrictAuditPublisher.class))
        .run(context -> assertThat(context).hasFailed());
    imperative()
        .withBean(TokenBudget.class, () -> budget(true))
        .run(context -> assertThat(context).hasFailed());
    imperative()
        .withBean(StrictAuditPublisher.class, () -> mock(StrictAuditPublisher.class))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void unknownVersionAndDuplicateProfilesFailInsteadOfChangingCanonicalization() {
    var ready =
        imperative()
            .withBean(TokenBudget.class, () -> budget(true))
            .withBean(StrictAuditPublisher.class, () -> mock(StrictAuditPublisher.class));
    ready
        .withPropertyValues("axiz.tokenization.vault.types.DNI.version=2")
        .run(context -> assertThat(context).hasFailed());
    ready
        .withBean(TokenProfile.class, TextTokenProfiles::dniPe)
        .run(context -> assertThat(context).hasFailed());
    ready
        .withPropertyValues("axiz.tokenization.vault.auth.fixed-tenant=tenant-a")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void reactiveCompositionNeverSatisfiesNativeAuditWithBlockingPublisher() {
    reactive()
        .withBean(AsyncStrictAuditPublisher.class, () -> mock(AsyncStrictAuditPublisher.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AsyncVaultOperations.class)
                    .doesNotHaveBean(VaultOperations.class));
    reactive()
        .withBean(StrictAuditPublisher.class, () -> mock(StrictAuditPublisher.class))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void primaryAndCustomEnginesCannotHideDuplicateOrAbsentSecurityCollaborators() {
    imperative()
        .withBean(TokenBudget.class, () -> budget(true))
        .withBean(StrictAuditPublisher.class, () -> mock(StrictAuditPublisher.class))
        .withBean(
            "secondVault",
            TokenVault.class,
            () -> mock(TokenVault.class),
            definition -> definition.setPrimary(true))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "Exactly one token vault collaborator is required: TokenVault");
            });
    imperative()
        .withBean(VaultOperations.class, () -> mock(VaultOperations.class))
        .withBean(StrictAuditPublisher.class, () -> mock(StrictAuditPublisher.class))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "Exactly one token vault collaborator is required: TokenBudget");
            });
  }
}

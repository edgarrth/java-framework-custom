package pe.axiz.spring.tokenization.vault;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.tokenization.TextTokenProfiles;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.AsyncTokenReconciliationLog;
import pe.axiz.core.tokenization.vault.AsyncTokenVault;
import pe.axiz.core.tokenization.vault.AsyncTokenizationAudit;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.AsyncVaultTokenizer;
import pe.axiz.core.tokenization.vault.SecureTokenGenerator;
import pe.axiz.core.tokenization.vault.TokenAccessPolicy;
import pe.axiz.core.tokenization.vault.TokenBudget;
import pe.axiz.core.tokenization.vault.TokenReconciliationAlarm;
import pe.axiz.core.tokenization.vault.TokenReconciliationLog;
import pe.axiz.core.tokenization.vault.TokenTypePolicy;
import pe.axiz.core.tokenization.vault.TokenVault;
import pe.axiz.core.tokenization.vault.TokenizationAudit;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.core.tokenization.vault.VaultPolicy;
import pe.axiz.core.tokenization.vault.VaultTokenizer;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Explicit server composition. Missing required collaborators fail startup instead of disabling it.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.tokenization.AxizTokenizationRemoteAutoConfiguration",
      "pe.axiz.spring.crypto.AxizCryptoAutoConfiguration",
      "pe.axiz.spring.crypto.kms.AxizCryptoKmsAutoConfiguration",
      "pe.axiz.spring.audit.AxizStrictAuditAutoConfiguration",
      "pe.axiz.spring.tokenization.vault.dynamodb.AxizTokenizationDynamoDbAutoConfiguration",
      "pe.axiz.spring.tokenization.vault.redis.AxizTokenizationRedisAutoConfiguration",
      "pe.axiz.spring.tokenization.pan.AxizTokenizationPanAutoConfiguration"
    })
@EnableConfigurationProperties(AxizTokenizationVaultProperties.class)
public class AxizTokenizationVaultAutoConfiguration {
  private static void unique(ListableBeanFactory beans, Class<?>... contracts) {
    for (Class<?> contract : contracts) {
      if (beans.getBeanNamesForType(contract, true, true).length != 1)
        throw new IllegalStateException(
            "Exactly one token vault collaborator is required: " + contract.getSimpleName());
    }
  }

  @Bean
  SecureTokenGenerator axizVaultTokenGenerator() {
    return new SecureTokenGenerator();
  }

  @Bean
  VerifiedTokenCaller axizVerifiedTokenCaller(AxizTokenizationVaultProperties properties) {
    return new VerifiedTokenCaller(properties.auth());
  }

  @Bean
  VaultPolicy axizVaultPolicy(
      AxizTokenizationVaultProperties properties,
      ObjectProvider<TokenProfile> registeredProfiles,
      TokenAccessPolicy access,
      BlindIndex indexes,
      ObjectProvider<Clock> clocks) {
    List<TokenProfile> profiles = new ArrayList<>();
    profiles.add(TextTokenProfiles.dniPe());
    profiles.add(TextTokenProfiles.identity());
    registeredProfiles.orderedStream().forEach(profiles::add);
    if (profiles.size() > 128)
      throw new IllegalStateException("Too many registered token profiles");
    Map<String, TokenProfile> registry = new HashMap<>();
    for (TokenProfile profile : profiles) {
      String id = AxizTokenizationVaultProperties.identifier(profile.id(), 64);
      if (profile.version() < 1
          || registry.putIfAbsent(id + "#" + profile.version(), profile) != null)
        throw new IllegalStateException("Duplicate or invalid registered token profile");
    }
    List<TokenTypePolicy> policies = new ArrayList<>();
    properties
        .types()
        .forEach(
            (name, configured) -> {
              TokenProfile profile =
                  registry.get(configured.profile() + "#" + configured.version());
              if (profile == null)
                throw new IllegalStateException("Unregistered token profile/version");
              policies.add(
                  TokenTypePolicy.builder()
                      .type(new TokenType(name))
                      .profile(profile)
                      .keysetId(configured.keyset())
                      .domains(
                          configured.domains().stream()
                              .map(TokenDomain::new)
                              .collect(Collectors.toUnmodifiableSet()))
                      .purposes(configured.purposes())
                      .retention(configured.retention())
                      .metadataKeys(configured.metadataKeys())
                      .panException(configured.panException())
                      .build());
            });
    return new VaultPolicy(
        policies,
        access,
        indexes,
        properties.auth().authorityPrefix(),
        clocks.getIfAvailable(Clock::systemUTC));
  }

  /** Imperative composition has no asynchronous provider fallback. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnImperativeApplication
  static class Imperative {
    @Bean
    @ConditionalOnMissingBean(Tokenizer.class)
    Tokenizer axizCallerBoundTokenizer(VaultOperations engine, VerifiedTokenCaller callers) {
      return new CallerBoundTokenizer(engine, callers);
    }

    @Bean
    SmartInitializingSingleton axizVaultRequirements(ListableBeanFactory beans) {
      return () -> {
        unique(
            beans,
            VaultOperations.class,
            VaultPolicy.class,
            TokenAccessPolicy.class,
            BlindIndex.class,
            TokenVault.class,
            EnvelopeEncryptor.class,
            TokenBudget.class,
            StrictAuditPublisher.class,
            TokenizationAudit.class,
            TokenReconciliationLog.class,
            TokenReconciliationAlarm.class);
        if (!beans.getBean(TokenBudget.class).distributed())
          throw new IllegalStateException("A distributed token budget is required");
      };
    }

    @Bean
    TokenizationAudit axizStrictTokenizationAudit(
        StrictAuditPublisher publisher, SecureTokenGenerator tokens, Environment environment) {
      return new StrictTokenizationAudit(
          publisher,
          environment.getRequiredProperty("spring.application.name"),
          tokens::operationId);
    }

    @Bean
    @ConditionalOnMissingBean(VaultOperations.class)
    VaultOperations axizVaultOperations(
        VaultPolicy policy,
        TokenVault vault,
        EnvelopeEncryptor encryption,
        SecureTokenGenerator tokens,
        TokenBudget budget,
        TokenizationAudit audit,
        TokenReconciliationLog reconciliation,
        TokenReconciliationAlarm alarm) {
      if (!budget.distributed())
        throw new IllegalStateException("A distributed token budget is required");
      return VaultTokenizer.builder()
          .policy(policy)
          .vault(vault)
          .encryption(encryption)
          .tokens(tokens)
          .budget(budget)
          .audit(audit)
          .reconciliation(reconciliation)
          .alarm(alarm)
          .operationIds(tokens::operationId)
          .build();
    }
  }

  /** The reactive stack requires native collaborators; it cannot wrap the imperative engine. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class Native {
    @Bean
    ReactiveCallerBoundTokenizer axizReactiveCallerBoundTokenizer(
        AsyncVaultOperations engine, VerifiedTokenCaller callers) {
      return new ReactiveCallerBoundTokenizer(engine, callers);
    }

    @Bean
    SmartInitializingSingleton axizAsyncVaultRequirements(ListableBeanFactory beans) {
      return () -> {
        unique(
            beans,
            AsyncVaultOperations.class,
            VaultPolicy.class,
            TokenAccessPolicy.class,
            BlindIndex.class,
            AsyncTokenVault.class,
            AsyncEnvelopeEncryptor.class,
            AsyncTokenBudget.class,
            AsyncStrictAuditPublisher.class,
            AsyncTokenizationAudit.class,
            AsyncTokenReconciliationLog.class,
            TokenReconciliationAlarm.class);
        if (!beans.getBean(AsyncTokenBudget.class).distributed())
          throw new IllegalStateException("A distributed token budget is required");
      };
    }

    @Bean
    AsyncTokenizationAudit axizAsyncStrictTokenizationAudit(
        AsyncStrictAuditPublisher publisher, SecureTokenGenerator tokens, Environment environment) {
      return new AsyncStrictTokenizationAudit(
          publisher,
          environment.getRequiredProperty("spring.application.name"),
          tokens::operationId);
    }

    @Bean
    @ConditionalOnMissingBean(AsyncVaultOperations.class)
    AsyncVaultOperations axizAsyncVaultOperations(
        VaultPolicy policy,
        AsyncTokenVault vault,
        AsyncEnvelopeEncryptor encryption,
        SecureTokenGenerator tokens,
        AsyncTokenBudget budget,
        AsyncTokenizationAudit audit,
        AsyncTokenReconciliationLog reconciliation,
        TokenReconciliationAlarm alarm) {
      if (!budget.distributed())
        throw new IllegalStateException("A distributed token budget is required");
      return AsyncVaultTokenizer.builder()
          .policy(policy)
          .vault(vault)
          .encryption(encryption)
          .tokens(tokens)
          .budget(budget)
          .audit(audit)
          .reconciliation(reconciliation)
          .alarm(alarm)
          .operationIds(tokens::operationId)
          .build();
    }
  }
}

package pe.axiz.spring.tokenization.vault.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.security.redis.RedisAsyncRateLimiter;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.adapter.tokenization.redis.RedisAsyncTokenBudget;
import pe.axiz.adapter.tokenization.redis.RedisTokenBudget;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/** Explicit Redis budget integration; no local fallback or mutation of a shared Redis client. */
@AutoConfiguration(
    afterName = "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration",
    beforeName = "pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration")
@EnableConfigurationProperties(AxizTokenizationRedisProperties.class)
public class AxizTokenizationRedisAutoConfiguration {
  @Bean(destroyMethod = "close")
  TokenRedisResources axizTokenBudgetRedisResources(
      AxizTokenizationRedisProperties properties, Environment environment, SecretResolver secrets) {
    return TokenRedisResources.open(
        properties.connection(),
        DataTransportSecurityPolicy.from(environment::getProperty),
        secrets);
  }

  @Bean
  @ConditionalOnImperativeApplication
  TokenBudget axizRedisTokenBudget(
      TokenRedisResources resources,
      AxizTokenizationRedisProperties properties,
      TokenBudgetLeaseAlarm alarm) {
    return new RedisTokenBudget(
        resources.connection(),
        properties.namespace(),
        properties.policy(),
        alarm,
        new RedisRateLimiter(resources.connection().sync(), "", false));
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  AsyncTokenBudget axizRedisAsyncTokenBudget(
      TokenRedisResources resources,
      AxizTokenizationRedisProperties properties,
      TokenBudgetLeaseAlarm alarm) {
    return new RedisAsyncTokenBudget(
        resources.connection(),
        properties.namespace(),
        properties.policy(),
        alarm,
        new RedisAsyncRateLimiter(resources.connection().async(), ""));
  }
}

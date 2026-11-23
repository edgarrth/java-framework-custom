package pe.axiz.spring.tokenization.vault.redis;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.security.redis.RedisAsyncRateLimiter;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.adapter.tokenization.redis.RedisAsyncTokenBudget;
import pe.axiz.adapter.tokenization.redis.RedisTokenBudget;
import pe.axiz.adapter.tokenization.redis.TokenBudgetOwner;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Integración explícita de presupuestos en Redis; sin respaldo local ni modificación de un cliente
 * Redis compartido.
 */
@AutoConfiguration(
    afterName = "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration",
    beforeName = "pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration")
@EnableConfigurationProperties(AxizTokenizationRedisProperties.class)
public class AxizTokenizationRedisAutoConfiguration {
  @Bean
  TokenBudgetOwner axizTokenBudgetOwner(Environment environment) {
    TokenBudgetOwner process = TokenBudgetOwner.process();
    String instance =
        environment.getProperty("axiz.tokenization.vault.redis.instance-id", process.instance());
    TokenBudgetOwner owner = new TokenBudgetOwner(instance, process.incarnation());
    LoggerFactory.getLogger(AxizTokenizationRedisAutoConfiguration.class)
        .info(
            "Token budget owner initialized; instance={}, incarnation={}",
            owner.instance(),
            owner.incarnation());
    return owner;
  }

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
      TokenBudgetLeaseAlarm alarm,
      TokenBudgetOwner owner) {
    return new RedisTokenBudget(
        resources.connection(),
        properties.namespace(),
        properties.policy(),
        alarm,
        new RedisRateLimiter(resources.connection().sync(), "", false),
        owner);
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  AsyncTokenBudget axizRedisAsyncTokenBudget(
      TokenRedisResources resources,
      AxizTokenizationRedisProperties properties,
      TokenBudgetLeaseAlarm alarm,
      TokenBudgetOwner owner) {
    return new RedisAsyncTokenBudget(
        resources.connection(),
        properties.namespace(),
        properties.policy(),
        alarm,
        new RedisAsyncRateLimiter(resources.connection().async(), ""),
        owner);
  }
}

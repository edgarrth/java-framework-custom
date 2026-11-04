package pe.axiz.payments.beneficiaries.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.cache.AxizCache;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.spring.cache.AxizCaches;

/** Beans of the service: business policy and the beneficiaries cache. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BeneficiariesProperties.class)
class BeneficiariesConfiguration {

  @Bean
  BeneficiaryPolicy beneficiaryPolicy(BeneficiariesProperties properties) {
    return new BeneficiaryPolicy(properties.maxPerCustomer());
  }

  /** Single beneficiaries; TTL in {@code axiz.cache.caches.beneficiaries.ttl}. */
  @Bean
  AxizCache beneficiariesCache(AxizCaches caches) {
    return caches.cache("beneficiaries");
  }
}

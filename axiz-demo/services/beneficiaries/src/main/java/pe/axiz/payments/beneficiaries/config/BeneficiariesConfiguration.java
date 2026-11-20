package pe.axiz.payments.beneficiaries.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;

/** Beans of the service: business policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BeneficiariesProperties.class)
class BeneficiariesConfiguration {

  @Bean
  BeneficiaryPolicy beneficiaryPolicy(BeneficiariesProperties properties) {
    return new BeneficiaryPolicy(properties.maxPerCustomer());
  }
}

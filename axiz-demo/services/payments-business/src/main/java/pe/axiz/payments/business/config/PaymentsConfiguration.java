package pe.axiz.payments.business.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.money.Money;
import pe.axiz.core.time.BusinessCalendar;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.payments.business.domain.PaymentLimits;

/** Domain policies built from configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsConfiguration {

  @Bean
  PaymentLimits paymentLimits(PaymentsProperties properties) {
    Map<String, Money> limits = new LinkedHashMap<>();
    properties
        .dailyLimits()
        .forEach((currency, amount) -> limits.put(currency, Money.of(amount, currency)));
    return new PaymentLimits(limits);
  }

  @Bean
  CutoffTime paymentsCutoff(PaymentsProperties properties, BusinessCalendar calendar) {
    return new CutoffTime(properties.cutoff(), calendar);
  }
}

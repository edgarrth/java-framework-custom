package pe.axiz.payments.beneficiaries.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of beneficiaries ({@code beneficiaries.*}, ConfigMap per environment).
 *
 * @param maxPerCustomer active beneficiaries a customer may have
 */
@ConfigurationProperties("beneficiaries")
public record BeneficiariesProperties(@DefaultValue("50") int maxPerCustomer) {}

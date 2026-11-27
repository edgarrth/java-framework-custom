package pe.axiz.payments.beneficiaries.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de beneficiaries ({@code beneficiaries.*}, ConfigMap por entorno).
 *
 * @param maxPerCustomer beneficiarios activos que puede tener un cliente
 */
@ConfigurationProperties("beneficiaries")
public record BeneficiariesProperties(@DefaultValue("50") int maxPerCustomer) {}

package pe.axiz.spring.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.crypto.PayloadProtectionMode;

/**
 * Protection of serialized saga state; envelope mode requires explicit key-provider configuration.
 */
@ConfigurationProperties("axiz.saga")
public record AxizSagaProtectionProperties(
    @DefaultValue("NONE") PayloadProtectionMode contextProtection) {}

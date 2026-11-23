package pe.axiz.spring.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.crypto.PayloadProtectionMode;

/**
 * Protección del estado serializado de la saga; el modo envelope requiere una configuración
 * explícita del proveedor de claves.
 */
@ConfigurationProperties("axiz.saga")
public record AxizSagaProtectionProperties(
    @DefaultValue("NONE") PayloadProtectionMode contextProtection) {}

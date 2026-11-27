package pe.axiz.spring.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.crypto.PayloadProtectionMode;

/**
 * Protección de los bytes de respuesta almacenados, seleccionada con independencia de la tecnología
 * de persistencia.
 */
@ConfigurationProperties("axiz.idempotency")
public record AxizIdempotencyProtectionProperties(
    @DefaultValue("NONE") PayloadProtectionMode responseProtection) {}

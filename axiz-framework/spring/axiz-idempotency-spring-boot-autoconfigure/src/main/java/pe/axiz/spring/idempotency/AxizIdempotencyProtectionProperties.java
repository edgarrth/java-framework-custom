package pe.axiz.spring.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.crypto.PayloadProtectionMode;

/** Protection of stored response bytes, selected independently from persistence technology. */
@ConfigurationProperties("axiz.idempotency")
public record AxizIdempotencyProtectionProperties(
    @DefaultValue("NONE") PayloadProtectionMode responseProtection) {}

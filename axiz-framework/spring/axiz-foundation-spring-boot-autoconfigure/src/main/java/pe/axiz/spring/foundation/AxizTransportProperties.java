package pe.axiz.spring.foundation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit local transport exception; production always requires TLS. */
@ConfigurationProperties("axiz.transport")
public record AxizTransportProperties(
    @DefaultValue("false") boolean allowPlainHttp,
    @DefaultValue("false") boolean allowInsecureDataStores) {}

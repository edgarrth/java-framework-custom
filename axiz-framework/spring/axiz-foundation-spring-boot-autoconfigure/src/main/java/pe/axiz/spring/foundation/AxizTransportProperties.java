package pe.axiz.spring.foundation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Excepción explícita de transporte local; producción siempre exige TLS. */
@ConfigurationProperties("axiz.transport")
public record AxizTransportProperties(
    @DefaultValue("false") boolean allowPlainHttp,
    @DefaultValue("false") boolean allowInsecureDataStores) {}

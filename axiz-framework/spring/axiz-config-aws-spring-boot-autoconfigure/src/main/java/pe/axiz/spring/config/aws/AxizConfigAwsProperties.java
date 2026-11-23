package pe.axiz.spring.config.aws;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Estos valores se seleccionan en la configuración del proceso antes de cargar Config Data. */
@ConfigurationProperties("axiz.config.parameter-store")
public record AxizConfigAwsProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("/axiz") String prefix,
    @Nullable String application) {}

package pe.axiz.spring.config.aws;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Select these values in process configuration before Config Data is loaded. */
@ConfigurationProperties("axiz.config.parameter-store")
public record AxizConfigAwsProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("/axiz") String prefix,
    @Nullable String application) {}

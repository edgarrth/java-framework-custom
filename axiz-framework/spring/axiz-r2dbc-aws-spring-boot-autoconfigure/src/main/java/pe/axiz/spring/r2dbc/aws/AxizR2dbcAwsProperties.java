package pe.axiz.spring.r2dbc.aws;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Política de tokens IAM; el proveedor de tokens acota el refresco antes de adquirir recursos. */
@ConfigurationProperties("axiz.r2dbc.iam")
public record AxizR2dbcAwsProperties(
    @DefaultValue("false") boolean enabled,
    @Nullable String region,
    @DefaultValue("10m") Duration refreshAfter) {}

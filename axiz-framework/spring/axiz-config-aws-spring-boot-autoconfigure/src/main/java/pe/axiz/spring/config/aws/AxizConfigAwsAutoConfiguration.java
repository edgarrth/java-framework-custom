package pe.axiz.spring.config.aws;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Expone la configuración de Parameter Store; la carga remota es responsabilidad de la integración
 * de bootstrap.
 */
@AutoConfiguration
@EnableConfigurationProperties(AxizConfigAwsProperties.class)
public final class AxizConfigAwsAutoConfiguration {}

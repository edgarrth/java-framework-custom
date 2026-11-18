package pe.axiz.spring.config.aws;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Exposes Parameter Store settings; remote loading is owned by the bootstrap integration. */
@AutoConfiguration
@EnableConfigurationProperties(AxizConfigAwsProperties.class)
public final class AxizConfigAwsAutoConfiguration {}

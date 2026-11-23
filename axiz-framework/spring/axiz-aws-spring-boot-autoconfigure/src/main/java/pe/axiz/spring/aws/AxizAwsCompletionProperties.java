package pe.axiz.spring.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración compartida de admisión de callbacks que las capacidades AWS usan de forma
 * independiente.
 */
@ConfigurationProperties("axiz.aws.completion")
public record AxizAwsCompletionProperties(
    @DefaultValue("2") int workers, @DefaultValue("256") int queueCapacity) {
  public AxizAwsCompletionProperties {
    if (workers < 1 || workers > 64 || queueCapacity < 1 || queueCapacity > 65536)
      throw new IllegalArgumentException(
          "AWS completion workers must be 1..64 and queue-capacity 1..65536");
  }
}

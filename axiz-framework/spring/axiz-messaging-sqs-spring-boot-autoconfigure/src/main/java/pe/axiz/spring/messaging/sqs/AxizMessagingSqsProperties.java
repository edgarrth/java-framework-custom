package pe.axiz.spring.messaging.sqs;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Colas de consumo SQS y política acotada de sondeo y visibilidad. */
@ConfigurationProperties("axiz.messaging.sqs")
public record AxizMessagingSqsProperties(
    @DefaultValue List<String> queues,
    @DefaultValue Map<String, String> deadLetterQueues,
    @DefaultValue("20s") Duration waitTime,
    @DefaultValue("10") int batchSize,
    @DefaultValue("60s") Duration visibilityTimeout) {
  public AxizMessagingSqsProperties {
    deadLetterQueues = Collections.unmodifiableMap(new LinkedHashMap<>(deadLetterQueues));
    queues = List.copyOf(queues);
  }
}

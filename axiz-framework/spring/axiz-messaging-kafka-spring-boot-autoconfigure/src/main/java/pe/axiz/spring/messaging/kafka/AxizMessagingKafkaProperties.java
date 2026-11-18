package pe.axiz.spring.messaging.kafka;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Kafka brokers, delivery policy and independently selected authentication integration. */
@ConfigurationProperties("axiz.messaging.kafka")
public record AxizMessagingKafkaProperties(
    @Nullable String bootstrapServers,
    @DefaultValue("configured") String authentication,
    @DefaultValue List<String> topics,
    @Nullable String groupId,
    @DefaultValue("1") int concurrency,
    @DefaultValue(".dlq") String deadLetterSuffix,
    @DefaultValue("10s") Duration sendTimeout) {
  public AxizMessagingKafkaProperties {
    topics = List.copyOf(topics);
  }
}

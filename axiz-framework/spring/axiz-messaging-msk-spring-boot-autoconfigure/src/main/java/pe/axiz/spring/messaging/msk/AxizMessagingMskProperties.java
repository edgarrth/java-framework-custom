package pe.axiz.spring.messaging.msk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Política MSK IAM; se selecciona con axiz.messaging.kafka.authentication=msk. */
@ConfigurationProperties("axiz.messaging.kafka")
public record AxizMessagingMskProperties(@DefaultValue("true") boolean iam) {}

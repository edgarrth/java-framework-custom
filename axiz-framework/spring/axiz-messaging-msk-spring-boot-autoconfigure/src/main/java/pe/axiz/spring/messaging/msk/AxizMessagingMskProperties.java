package pe.axiz.spring.messaging.msk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** MSK IAM policy; selected with axiz.messaging.kafka.authentication=msk. */
@ConfigurationProperties("axiz.messaging.kafka")
public record AxizMessagingMskProperties(@DefaultValue("true") boolean iam) {}

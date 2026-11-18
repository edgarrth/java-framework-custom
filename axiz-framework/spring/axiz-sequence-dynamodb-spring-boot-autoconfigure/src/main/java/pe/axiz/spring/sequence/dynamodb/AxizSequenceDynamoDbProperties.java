package pe.axiz.spring.sequence.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** DYNAMODB storage settings for sequence. */
@ConfigurationProperties("axiz.sequence")
public record AxizSequenceDynamoDbProperties(@DefaultValue("axiz_sequence") String table) {}

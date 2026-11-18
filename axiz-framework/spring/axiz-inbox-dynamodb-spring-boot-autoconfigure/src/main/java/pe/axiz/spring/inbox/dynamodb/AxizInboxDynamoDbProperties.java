package pe.axiz.spring.inbox.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** DYNAMODB storage settings for inbox. */
@ConfigurationProperties("axiz.inbox")
public record AxizInboxDynamoDbProperties(@DefaultValue("axiz_inbox") String table) {}

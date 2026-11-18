package pe.axiz.spring.scheduling.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** DYNAMODB storage settings for scheduling. */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingDynamoDbProperties(@DefaultValue("shedlock") String table) {}

package pe.axiz.spring.inbox.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración de almacenamiento DYNAMODB para el inbox. */
@ConfigurationProperties("axiz.inbox")
public record AxizInboxDynamoDbProperties(@DefaultValue("axiz_inbox") String table) {}

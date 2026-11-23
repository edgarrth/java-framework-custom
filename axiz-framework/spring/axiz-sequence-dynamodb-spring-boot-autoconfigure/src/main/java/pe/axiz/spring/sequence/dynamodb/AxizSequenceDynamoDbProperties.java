package pe.axiz.spring.sequence.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración de almacenamiento DYNAMODB para las secuencias. */
@ConfigurationProperties("axiz.sequence")
public record AxizSequenceDynamoDbProperties(@DefaultValue("axiz_sequence") String table) {}

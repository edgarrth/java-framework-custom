package pe.axiz.spring.scheduling.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración de almacenamiento DYNAMODB para la planificación. */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingDynamoDbProperties(@DefaultValue("shedlock") String table) {}

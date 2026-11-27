package pe.axiz.spring.scheduling.r2dbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración de almacenamiento R2DBC para la planificación. */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingR2dbcProperties(@DefaultValue("shedlock") String table) {}

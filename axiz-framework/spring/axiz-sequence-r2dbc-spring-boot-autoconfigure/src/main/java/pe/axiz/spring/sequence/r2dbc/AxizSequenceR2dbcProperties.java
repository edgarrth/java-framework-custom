package pe.axiz.spring.sequence.r2dbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración de almacenamiento R2DBC para las secuencias. */
@ConfigurationProperties("axiz.sequence")
public record AxizSequenceR2dbcProperties(@DefaultValue("axiz_sequence") String table) {}

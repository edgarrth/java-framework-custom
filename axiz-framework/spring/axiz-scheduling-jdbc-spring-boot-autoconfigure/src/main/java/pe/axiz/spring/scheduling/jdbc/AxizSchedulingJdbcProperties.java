package pe.axiz.spring.scheduling.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.jdbc.SchemaInitializer;

/** Configuración de almacenamiento JDBC para la planificación. */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingJdbcProperties(
    @DefaultValue("shedlock") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

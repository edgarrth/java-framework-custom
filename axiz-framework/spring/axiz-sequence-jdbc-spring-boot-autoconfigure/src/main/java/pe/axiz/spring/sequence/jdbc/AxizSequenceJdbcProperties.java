package pe.axiz.spring.sequence.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.jdbc.SchemaInitializer;

/** Configuración de almacenamiento JDBC para las secuencias. */
@ConfigurationProperties("axiz.sequence")
public record AxizSequenceJdbcProperties(
    @DefaultValue("axiz_sequence") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

package pe.axiz.spring.sequence.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.jdbc.SchemaInitializer;

/** JDBC storage settings for sequence. */
@ConfigurationProperties("axiz.sequence")
public record AxizSequenceJdbcProperties(
    @DefaultValue("axiz_sequence") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

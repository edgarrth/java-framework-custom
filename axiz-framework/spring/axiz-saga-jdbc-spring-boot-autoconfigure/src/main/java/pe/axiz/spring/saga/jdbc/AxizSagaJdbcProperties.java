package pe.axiz.spring.saga.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/** JDBC storage settings; the neutral saga capability owns execution policy. */
@ConfigurationProperties("axiz.saga")
public record AxizSagaJdbcProperties(
    @DefaultValue("axiz_saga") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

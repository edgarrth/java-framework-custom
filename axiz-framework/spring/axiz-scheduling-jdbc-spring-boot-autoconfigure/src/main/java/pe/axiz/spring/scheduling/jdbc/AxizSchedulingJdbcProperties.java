package pe.axiz.spring.scheduling.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/** JDBC storage settings for scheduling. */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingJdbcProperties(
    @DefaultValue("shedlock") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

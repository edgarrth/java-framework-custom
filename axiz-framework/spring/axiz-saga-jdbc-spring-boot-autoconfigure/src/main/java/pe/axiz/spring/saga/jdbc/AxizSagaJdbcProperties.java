package pe.axiz.spring.saga.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.jdbc.SchemaInitializer;

/**
 * Configuración de almacenamiento JDBC; la capacidad neutral de sagas define la política de
 * ejecución.
 */
@ConfigurationProperties("axiz.saga")
public record AxizSagaJdbcProperties(
    @DefaultValue("axiz_saga") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

package pe.axiz.spring.inbox.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.jdbc.SchemaInitializer;

/** Configuración de almacenamiento JDBC para el inbox. */
@ConfigurationProperties("axiz.inbox")
public record AxizInboxJdbcProperties(
    @DefaultValue("axiz_inbox") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

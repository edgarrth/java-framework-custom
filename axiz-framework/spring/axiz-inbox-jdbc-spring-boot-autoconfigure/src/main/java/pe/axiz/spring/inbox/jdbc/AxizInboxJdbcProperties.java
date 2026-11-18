package pe.axiz.spring.inbox.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/** JDBC storage settings for inbox. */
@ConfigurationProperties("axiz.inbox")
public record AxizInboxJdbcProperties(
    @DefaultValue("axiz_inbox") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}

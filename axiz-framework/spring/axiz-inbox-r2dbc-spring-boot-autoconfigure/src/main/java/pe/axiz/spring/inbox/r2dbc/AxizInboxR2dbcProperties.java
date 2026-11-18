package pe.axiz.spring.inbox.r2dbc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** R2DBC storage settings for inbox. */
@ConfigurationProperties("axiz.inbox")
public record AxizInboxR2dbcProperties(
    @DefaultValue("axiz_inbox") String table, @DefaultValue("10s") Duration statementTimeout) {}

package pe.axiz.spring.saga;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/**
 * Orchestrated sagas ({@code axiz.saga.*}) with persisted state and compensation.
 *
 * @param table state table
 * @param initializeSchema when the table is created by the starter
 * @param stepAttempts attempts of a step on transient failures
 * @param compensationAttempts attempts of a compensation
 * @param initialDelay first retry delay
 * @param maxDelay retry delay cap
 */
@ConfigurationProperties("axiz.saga")
public record AxizSagaProperties(
    @DefaultValue("axiz_saga") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema,
    @DefaultValue("3") int stepAttempts,
    @DefaultValue("5") int compensationAttempts,
    @DefaultValue("200ms") Duration initialDelay,
    @DefaultValue("5s") Duration maxDelay) {}

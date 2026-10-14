package pe.axiz.spring.lock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Distributed locks ({@code axiz.lock.*}) to serialize work on one resource (e.g. an account).
 *
 * @param store {@code redis}, {@code dynamodb} or {@code memory} (single instance, tests)
 * @param table DynamoDB table
 * @param keyPrefix Redis key prefix
 * @param pollInterval wait between acquisition attempts
 */
@ConfigurationProperties("axiz.lock")
public record AxizLockProperties(
    @DefaultValue("memory") String store,
    @DefaultValue("axiz-locks") String table,
    @DefaultValue("axiz:lock:") String keyPrefix,
    @DefaultValue("100ms") Duration pollInterval) {}

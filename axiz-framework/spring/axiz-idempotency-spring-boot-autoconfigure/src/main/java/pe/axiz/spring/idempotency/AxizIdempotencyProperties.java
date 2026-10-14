package pe.axiz.spring.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API idempotency ({@code axiz.idempotency.*}) for operations annotated with {@code @Idempotent}.
 *
 * @param store {@code redis}, {@code dynamodb} or {@code memory} (single instance, tests only)
 * @param table DynamoDB table
 * @param keyPrefix Redis key prefix
 * @param inProgressTtl how long a key stays locked while the first request runs
 * @param retention how long completed responses are replayed
 * @param maxBodySize largest request body fingerprinted (larger requests are rejected with 413)
 * @param maxResponseSize largest response body stored for replay; a larger response is still
 *     returned and its status and headers are stored, but a replay carries no body and the header
 *     {@code Idempotent-Body-Omitted: true}
 */
@ConfigurationProperties("axiz.idempotency")
public record AxizIdempotencyProperties(
    @DefaultValue("memory") String store,
    @DefaultValue("axiz-idempotency") String table,
    @DefaultValue("axiz:idem:") String keyPrefix,
    @DefaultValue("30s") Duration inProgressTtl,
    @DefaultValue("24h") Duration retention,
    @DefaultValue("1048576") int maxBodySize,
    @DefaultValue("1048576") int maxResponseSize) {}

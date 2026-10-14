package pe.axiz.payments.settlement.job;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the settlement job ({@code settlement.*}).
 *
 * @param inputKey explicit object key to load (reprocessing); when empty, the file of the previous
 *     business day under {@code inputPrefix}
 * @param inputPrefix folder of the network files in the bucket
 * @param maxDuration upper bound of a run (cluster lock lease)
 */
@ConfigurationProperties("settlement")
public record SettlementProperties(
    @Nullable String inputKey,
    @DefaultValue("in/settlement/") String inputPrefix,
    @DefaultValue("2h") Duration maxDuration) {}

package pe.axiz.payments.business.config;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Business settings of payments ({@code payments.*}, ConfigMap per environment).
 *
 * @param cutoff orders received after this local time are processed the next business day
 * @param dailyLimits daily limit per currency code
 */
@ConfigurationProperties("payments")
public record PaymentsProperties(
    @DefaultValue("18:00") LocalTime cutoff, @DefaultValue Map<String, BigDecimal> dailyLimits) {}

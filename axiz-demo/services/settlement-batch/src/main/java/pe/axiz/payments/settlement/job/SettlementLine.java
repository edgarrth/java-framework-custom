package pe.axiz.payments.settlement.job;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * One settled (or returned) transfer of the network settlement file.
 *
 * @param endToEndId operation number sent with the transfer
 * @param transferId network id
 * @param creditorCci destination account
 * @param amount amount
 * @param currency currency code
 * @param status SETTLED or RETURNED
 * @param settledAt settlement time (network local time)
 */
public record SettlementLine(
    String endToEndId,
    String transferId,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
    BigDecimal amount,
    String currency,
    String status,
    LocalDateTime settledAt) {}

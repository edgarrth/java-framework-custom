package pe.axiz.payments.settlement.job;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Una transferencia liquidada (o devuelta) del archivo de liquidación de la red.
 *
 * @param endToEndId número de operación enviado con la transferencia
 * @param transferId id en la red
 * @param creditorCci cuenta de destino
 * @param amount monto
 * @param currency código de moneda
 * @param status SETTLED o RETURNED
 * @param settledAt momento de la liquidación (hora local de la red)
 */
public record SettlementLine(
    String endToEndId,
    String transferId,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
    BigDecimal amount,
    String currency,
    String status,
    LocalDateTime settledAt) {}

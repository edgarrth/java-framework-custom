package pe.axiz.payments.beneficiaries.model;

import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Datos de un beneficiario por registrar (con formato ya validado por el contrato).
 *
 * @param alias nombre que le asigna el cliente
 * @param holderName titular de la cuenta
 * @param cci código de cuenta interbancario (20 dígitos)
 * @param currency PEN o USD
 */
public record NewBeneficiary(
    String alias,
    @Sensitive(level = SensitivityLevel.MEDIUM) String holderName,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.PARTIAL) String cci,
    String currency) {}

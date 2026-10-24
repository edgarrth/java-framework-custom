package pe.axiz.payments.beneficiaries.model;

import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Data of a beneficiary to register (already validated in format by the contract).
 *
 * @param alias name the customer gives it
 * @param holderName account holder
 * @param cci inter-bank account code (20 digits)
 * @param currency PEN or USD
 */
public record NewBeneficiary(
    String alias,
    @Sensitive(level = SensitivityLevel.MEDIUM) String holderName,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.PARTIAL) String cci,
    String currency) {}

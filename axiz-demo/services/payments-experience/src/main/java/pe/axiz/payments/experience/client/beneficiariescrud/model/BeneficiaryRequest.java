package pe.axiz.payments.experience.client.beneficiariescrud.model;

/**
 * BeneficiaryRequest.
 *
 * @param alias alias
 * @param holderName holderName
 * @param cci cci
 * @param currency currency
 */
public record BeneficiaryRequest(String alias, String holderName, String cci, String currency) {}

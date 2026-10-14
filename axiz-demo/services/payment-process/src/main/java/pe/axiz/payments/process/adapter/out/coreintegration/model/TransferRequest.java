package pe.axiz.payments.process.adapter.out.coreintegration.model;

import java.math.BigDecimal;

/**
 * TransferRequest.
 *
 * @param creditorCci creditorCci
 * @param amount amount
 * @param currency currency
 * @param reference reference
 */
public record TransferRequest(
    String creditorCci, BigDecimal amount, String currency, String reference) {}

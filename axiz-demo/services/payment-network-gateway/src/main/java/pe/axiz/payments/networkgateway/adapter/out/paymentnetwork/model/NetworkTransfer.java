package pe.axiz.payments.networkgateway.adapter.out.paymentnetwork.model;

import java.math.BigDecimal;

/**
 * NetworkTransfer.
 *
 * @param destinationCci destinationCci
 * @param amount amount
 * @param currency currency
 * @param endToEndId endToEndId
 */
public record NetworkTransfer(
    String destinationCci, BigDecimal amount, String currency, String endToEndId) {}

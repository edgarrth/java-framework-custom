package pe.axiz.payments.process.adapter.out.paymentnetworkgateway.model;

import org.jspecify.annotations.Nullable;

/**
 * Transfer.
 *
 * @param transferId transferId
 * @param status status
 * @param reference reference
 */
public record Transfer(
    @Nullable String transferId, @Nullable String status, @Nullable String reference) {}

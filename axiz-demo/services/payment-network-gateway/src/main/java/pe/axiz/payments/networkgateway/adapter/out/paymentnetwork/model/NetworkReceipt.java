package pe.axiz.payments.networkgateway.adapter.out.paymentnetwork.model;

import org.jspecify.annotations.Nullable;

/**
 * NetworkReceipt.
 *
 * @param id id
 * @param result result
 * @param endToEndId endToEndId
 */
public record NetworkReceipt(
    @Nullable String id, @Nullable String result, @Nullable String endToEndId) {}

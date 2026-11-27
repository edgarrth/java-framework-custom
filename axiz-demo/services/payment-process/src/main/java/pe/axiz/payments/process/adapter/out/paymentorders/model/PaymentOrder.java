package pe.axiz.payments.process.adapter.out.paymentorders.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * PaymentOrder.
 *
 * @param id id
 * @param operationNumber operationNumber
 * @param customerId customerId
 * @param status status
 * @param amount amount
 * @param currency currency
 * @param creditorCci enmascarada
 * @param processingDate processingDate
 * @param createdAt createdAt
 */
public record PaymentOrder(
    @Nullable String id,
    @Nullable String operationNumber,
    @Nullable String customerId,
    @Nullable PaymentStatus status,
    @Nullable BigDecimal amount,
    @Nullable String currency,
    @Nullable String creditorCci,
    @Nullable LocalDate processingDate,
    @Nullable OffsetDateTime createdAt) {}

package pe.axiz.payments.business.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code payment_order}. */
interface PaymentOrderJpaRepository extends JpaRepository<PaymentOrderEntity, UUID> {

  Page<PaymentOrderEntity> findByCustomerId(String customerId, Pageable pageable);

  Optional<PaymentOrderEntity> findByCustomerIdAndRequestKey(String customerId, String requestKey);

  /**
   * Adds the amount to the customer's day only while it fits in the limit, in one statement: the
   * conflicting row is locked and re-checked by PostgreSQL, so concurrent reservations serialize.
   * Returns 0 when it does not fit.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO payment_daily_usage (customer_id, currency, processing_date, reserved)"
              + " VALUES (:customerId, :currency, :date, :amount)"
              + " ON CONFLICT (customer_id, currency, processing_date) DO UPDATE"
              + " SET reserved = payment_daily_usage.reserved + EXCLUDED.reserved"
              + " WHERE payment_daily_usage.reserved + EXCLUDED.reserved <= :limit",
      nativeQuery = true)
  int reserve(
      @Param("customerId") String customerId,
      @Param("currency") String currency,
      @Param("date") LocalDate date,
      @Param("amount") BigDecimal amount,
      @Param("limit") BigDecimal limit);

  @Modifying
  @Query(
      value =
          "UPDATE payment_daily_usage SET reserved = GREATEST(reserved - :amount, 0)"
              + " WHERE customer_id = :customerId AND currency = :currency"
              + " AND processing_date = :date",
      nativeQuery = true)
  int release(
      @Param("customerId") String customerId,
      @Param("currency") String currency,
      @Param("date") LocalDate date,
      @Param("amount") BigDecimal amount);

  @Query(
      value =
          "SELECT COALESCE(MAX(reserved), 0) FROM payment_daily_usage WHERE customer_id ="
              + " :customerId AND currency = :currency AND processing_date = :date",
      nativeQuery = true)
  BigDecimal reserved(
      @Param("customerId") String customerId,
      @Param("currency") String currency,
      @Param("date") LocalDate date);

  boolean existsByCustomerIdAndCreditorCciIndexAndAmountAndProcessingDateAndStatusNot(
      String customerId,
      String creditorCciIndex,
      BigDecimal amount,
      LocalDate processingDate,
      String status);
}

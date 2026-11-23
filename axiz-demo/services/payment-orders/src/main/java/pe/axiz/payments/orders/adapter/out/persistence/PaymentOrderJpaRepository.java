package pe.axiz.payments.orders.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;

/** Acceso de Spring Data a {@code payment_order}. */
interface PaymentOrderJpaRepository extends JpaRepository<PaymentOrderEntity, UUID> {

  Page<PaymentOrderEntity> findByCustomerId(String customerId, Pageable pageable);

  Optional<PaymentOrderEntity> findByCustomerIdAndRequestKey(String customerId, String requestKey);

  /**
   * Suma el monto al día del cliente solo mientras quepa en el límite, en una única sentencia:
   * PostgreSQL bloquea la fila en conflicto y la vuelve a verificar, por lo que las reservas
   * concurrentes se serializan. Devuelve 0 cuando no cabe.
   */
  @Modifying
  @NativeQuery(
      "INSERT INTO payment_daily_usage (customer_id, currency, processing_date, reserved)"
          + " VALUES (:customerId, :currency, :date, :amount)"
          + " ON CONFLICT (customer_id, currency, processing_date) DO UPDATE"
          + " SET reserved = payment_daily_usage.reserved + EXCLUDED.reserved"
          + " WHERE payment_daily_usage.reserved + EXCLUDED.reserved <= :limit")
  int reserve(
      @Param("customerId") String customerId,
      @Param("currency") String currency,
      @Param("date") LocalDate date,
      @Param("amount") BigDecimal amount,
      @Param("limit") BigDecimal limit);

  @Modifying
  @NativeQuery(
      "UPDATE payment_daily_usage SET reserved = GREATEST(reserved - :amount, 0)"
          + " WHERE customer_id = :customerId AND currency = :currency"
          + " AND processing_date = :date")
  int release(
      @Param("customerId") String customerId,
      @Param("currency") String currency,
      @Param("date") LocalDate date,
      @Param("amount") BigDecimal amount);

  @NativeQuery(
      "SELECT COALESCE(MAX(reserved), 0) FROM payment_daily_usage WHERE customer_id ="
          + " :customerId AND currency = :currency AND processing_date = :date")
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

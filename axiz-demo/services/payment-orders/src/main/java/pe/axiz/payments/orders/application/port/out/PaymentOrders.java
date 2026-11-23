package pe.axiz.payments.orders.application.port.out;

import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.money.Money;
import pe.axiz.payments.orders.domain.PaymentOrder;

/** Almacenamiento de órdenes de pago. */
public interface PaymentOrders {

  /**
   * Almacena los cambios de una orden existente; nunca inserta una orden inexistente. El caso de
   * uso carga y guarda dentro de la misma transacción. Que falte una orden persistida es una falla
   * de infraestructura.
   */
  void save(PaymentOrder order);

  /**
   * Inserta una orden nueva junto con la clave de solicitud del cliente, única por cliente, y la
   * escribe de inmediato para que una solicitud concurrente con la misma clave falle aquí. Devuelve
   * la representación persistida de referencia, incluida su marca de tiempo de creación con la
   * precisión de la base de datos.
   *
   * @throws pe.axiz.core.errors.BusinessException {@code CONCURRENT_REQUEST} cuando otra solicitud
   *     con la misma clave se almacenó primero
   */
  PaymentOrder create(PaymentOrder order, @Nullable String requestKey);

  /** La orden que un cliente registró con una clave de solicitud, si existe. */
  Optional<PaymentOrder> findByRequestKey(String customerId, String requestKey);

  Optional<PaymentOrder> find(String id);

  Page<PaymentOrder> byCustomer(String customerId, PageRequest page);

  /**
   * Suma de forma atómica {@code amount} a lo que el cliente reservó para esa fecha de
   * procesamiento y esa moneda, solo si el total se mantiene dentro de {@code limit}. La fila de
   * reserva queda bloqueada hasta que termina la transacción, por lo que las órdenes concurrentes
   * del mismo cliente y día se serializan: dos órdenes nunca pueden caber ambas en el mismo monto
   * restante.
   *
   * @return false cuando el monto no cabe (no se reserva nada)
   */
  boolean reserveDaily(String customerId, Money amount, LocalDate processingDate, Money limit);

  /** Devuelve el monto de una orden que no se pagará (rechazada). */
  void releaseDaily(String customerId, Money amount, LocalDate processingDate);

  /** Monto reservado por el cliente para una fecha de procesamiento, en una moneda. */
  Money reservedOn(String customerId, String currency, LocalDate processingDate);

  /** Indica si el cliente ya ordenó el mismo monto al mismo acreedor en esa fecha. */
  boolean exists(String customerId, String creditorCci, Money amount, LocalDate processingDate);
}

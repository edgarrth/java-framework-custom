package pe.axiz.payments.orders.application.access;

import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.extension.cqrs.Caller;

/**
 * Quién puede hacer qué con las órdenes de pago.
 *
 * <ul>
 *   <li>Clientes (token del canal retransmitido por payments-ux): solo sus propias órdenes; crean
 *       órdenes y las leen, nunca cambian su estado.
 *   <li>El proceso de pagos (client credentials con {@code payments:process}): mueve las órdenes
 *       por sus estados y las lee.
 *   <li>Servicios de back-office o de reportes ({@code payments:read}): leen cualquier orden.
 * </ul>
 */
public final class PaymentAuthorities {

  public static final String PROCESS = "SCOPE_payments:process";
  public static final String READ = "SCOPE_payments:read";

  private PaymentAuthorities() {}

  /**
   * Un cliente que actúa por sí mismo; los servicios nunca crean órdenes en nombre de los clientes.
   */
  static void requireCustomer(Caller caller, String customerId) {
    if (!caller.isUser(customerId)) {
      throw new AccessException(CoreErrors.FORBIDDEN, "caller is not customer " + customerId);
    }
  }

  /** El propietario, o un servicio autorizado a leer cualquier orden. */
  static boolean canRead(Caller caller, String customerId) {
    return caller.isMachine() ? caller.hasAnyAuthority(PROCESS, READ) : caller.isUser(customerId);
  }
}

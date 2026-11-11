package pe.axiz.payments.orders.application.access;

import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.extension.cqrs.Caller;

/**
 * Who may do what with payment orders.
 *
 * <ul>
 *   <li>Customers (channel token relayed by payments-ux): only their own orders; they create orders
 *       and read them, never change their status.
 *   <li>The payment process (client credentials with {@code payments:process}): moves orders
 *       through their statuses and reads them.
 *   <li>Back-office or reporting services ({@code payments:read}): read any order.
 * </ul>
 */
public final class PaymentAuthorities {

  public static final String PROCESS = "SCOPE_payments:process";
  public static final String READ = "SCOPE_payments:read";

  private PaymentAuthorities() {}

  /** A customer acting for itself; services never create orders on behalf of customers. */
  static void requireCustomer(Caller caller, String customerId) {
    if (!caller.isUser(customerId)) {
      throw new AccessException(CoreErrors.FORBIDDEN, "caller is not customer " + customerId);
    }
  }

  /** The owner, or a service allowed to read any order. */
  static boolean canRead(Caller caller, String customerId) {
    return caller.isMachine() ? caller.hasAnyAuthority(PROCESS, READ) : caller.isUser(customerId);
  }
}

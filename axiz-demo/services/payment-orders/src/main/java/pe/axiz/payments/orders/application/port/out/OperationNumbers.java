package pe.axiz.payments.orders.application.port.out;

/** Human-readable operation numbers (unique per day). */
public interface OperationNumbers {

  String next();
}

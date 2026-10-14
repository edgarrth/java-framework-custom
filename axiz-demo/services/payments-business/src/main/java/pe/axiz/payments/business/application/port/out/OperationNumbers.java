package pe.axiz.payments.business.application.port.out;

/** Human-readable operation numbers (unique per day). */
public interface OperationNumbers {

  String next();
}

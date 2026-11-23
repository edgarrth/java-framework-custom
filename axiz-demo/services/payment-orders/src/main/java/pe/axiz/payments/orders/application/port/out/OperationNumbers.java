package pe.axiz.payments.orders.application.port.out;

/** Números de operación legibles para personas (únicos por día). */
public interface OperationNumbers {

  String next();
}

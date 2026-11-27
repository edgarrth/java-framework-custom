package pe.axiz.payments.orders.adapter.out.sequence;

import org.springframework.stereotype.Component;
import pe.axiz.payments.orders.application.port.out.OperationNumbers;
import pe.axiz.spring.sequence.Sequences;

/**
 * Números de operación a partir de la secuencia {@code operation} (prefijo, reinicio diario y ancho
 * configurados en {@code axiz.sequence.sequences.operation}).
 */
@Component
public class OperationNumberAdapter implements OperationNumbers {

  private final Sequences sequences;

  public OperationNumberAdapter(Sequences sequences) {
    this.sequences = sequences;
  }

  @Override
  public String next() {
    return sequences.next("operation");
  }
}

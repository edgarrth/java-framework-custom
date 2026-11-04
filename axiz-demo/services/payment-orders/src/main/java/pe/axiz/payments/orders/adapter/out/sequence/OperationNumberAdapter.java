package pe.axiz.payments.orders.adapter.out.sequence;

import org.springframework.stereotype.Component;
import pe.axiz.payments.orders.application.port.out.OperationNumbers;
import pe.axiz.spring.sequence.Sequences;

/**
 * Operation numbers from the {@code operation} sequence (prefix, daily reset and width configured
 * in {@code axiz.sequence.sequences.operation}).
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

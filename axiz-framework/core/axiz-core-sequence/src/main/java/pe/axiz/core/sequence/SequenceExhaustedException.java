package pe.axiz.core.sequence;

/** La secuencia superó los dígitos de su formato dentro del periodo. */
public class SequenceExhaustedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public SequenceExhaustedException(String prefix, String period) {
    super("Sequence " + prefix + " exhausted for period " + period);
  }
}

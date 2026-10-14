package pe.axiz.core.sequence;

/** The sequence exceeded the digits of its format within the period. */
public class SequenceExhaustedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public SequenceExhaustedException(String prefix, String period) {
    super("Sequence " + prefix + " exhausted for period " + period);
  }
}

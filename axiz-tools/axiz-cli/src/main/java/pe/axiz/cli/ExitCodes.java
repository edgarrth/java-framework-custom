package pe.axiz.cli;

/** Códigos de salida del proceso. */
final class ExitCodes {

  static final int OK = 0;
  static final int PROBLEMS = 1;
  static final int USAGE = 2;

  /**
   * Una falla inesperada de la propia CLI (un defecto que se debe reportar), no de la entrada del
   * usuario.
   */
  static final int INTERNAL = 3;

  private ExitCodes() {}
}

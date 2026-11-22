package pe.axiz.cli;

/** Process exit codes. */
final class ExitCodes {

  static final int OK = 0;
  static final int PROBLEMS = 1;
  static final int USAGE = 2;

  /** An unexpected failure of the CLI itself (a defect to report), not of the user's input. */
  static final int INTERNAL = 3;

  private ExitCodes() {}
}

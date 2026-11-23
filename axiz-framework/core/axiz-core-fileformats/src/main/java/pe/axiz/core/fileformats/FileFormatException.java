package pe.axiz.core.fileformats;

/** Registro mal formado en un archivo; lleva el número de línea. */
public class FileFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int line;

  public FileFormatException(String message, int line) {
    super(message + " at line " + line);
    this.line = line;
  }

  public int line() {
    return line;
  }
}

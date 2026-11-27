package pe.axiz.codegen.openapi;

/**
 * Una infracción de una regla del contrato.
 *
 * @param rule id de la regla (los mismos ids que el ruleset de Spectral)
 * @param severity {@code error} o {@code warn}
 * @param location ubicación con formato similar a JSONPath
 * @param message explicación
 */
public record LintFinding(String rule, String severity, String location, String message) {

  public boolean error() {
    return "error".equals(severity);
  }
}

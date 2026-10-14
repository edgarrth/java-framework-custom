package pe.axiz.codegen.openapi;

/**
 * A contract rule violation.
 *
 * @param rule rule id (same ids as the Spectral ruleset)
 * @param severity {@code error} or {@code warn}
 * @param location JSON-path-like location
 * @param message explanation
 */
public record LintFinding(String rule, String severity, String location, String message) {

  public boolean error() {
    return "error".equals(severity);
  }
}

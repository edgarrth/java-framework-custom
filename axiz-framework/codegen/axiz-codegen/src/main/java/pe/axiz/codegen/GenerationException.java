package pe.axiz.codegen;

import java.util.List;
import pe.axiz.codegen.openapi.LintFinding;

/** The request cannot be generated; carries every problem found, not only the first one. */
public class GenerationException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final transient List<String> problems;
  private final transient List<LintFinding> findings;

  public GenerationException(List<String> problems, List<LintFinding> findings) {
    super(String.join("; ", problems));
    this.problems = List.copyOf(problems);
    this.findings = List.copyOf(findings);
  }

  public List<String> problems() {
    return problems;
  }

  public List<LintFinding> findings() {
    return findings;
  }
}

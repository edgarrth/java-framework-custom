package pe.axiz.codegen.render;

import pe.axiz.codegen.openapi.Json;
import pe.axiz.codegen.openapi.OpenApiLinter;

/** Context-specific encoding of contract text inserted into generated Java source. */
public final class JavaSourceText {
  private JavaSourceText() {}

  /** Keeps text inside a single Javadoc line, including Java's pre-lexical Unicode escapes. */
  public static String javadoc(String text) {
    return text.replace("&", "&amp;")
        .replace("\\", "&#92;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("*/", "*&#47;")
        .replace("@", "&#64;")
        .replace('\r', ' ')
        .replace('\n', ' ');
  }

  /**
   * A quoted Java literal; JSON encoding also escapes backslashes, quotes and control characters.
   */
  public static String literal(String text) {
    return Json.write(text);
  }

  /** Safety validation shared by service contracts and third-party HTTP clients. */
  public static String parameterLiteral(String name) {
    if (!OpenApiLinter.safeParameterName(name))
      throw new IllegalArgumentException("Unsafe OpenAPI parameter name");
    return literal(name);
  }
}

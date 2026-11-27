package pe.axiz.codegen.render;

import pe.axiz.codegen.openapi.Json;
import pe.axiz.codegen.openapi.OpenApiLinter;

/**
 * Codificación según el contexto del texto del contrato que se inserta en el código Java generado.
 */
public final class JavaSourceText {
  private JavaSourceText() {}

  /**
   * Mantiene el texto dentro de una sola línea Javadoc, incluidos los escapes Unicode prelexicales
   * de Java.
   */
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
   * Un literal Java entre comillas; la codificación JSON también escapa barras invertidas, comillas
   * y caracteres de control.
   */
  public static String literal(String text) {
    return Json.write(text);
  }

  /**
   * Validación de seguridad compartida por los contratos de servicio y los clientes HTTP de
   * terceros.
   */
  public static String parameterLiteral(String name) {
    if (!OpenApiLinter.safeParameterName(name))
      throw new IllegalArgumentException("Unsafe OpenAPI parameter name");
    return literal(name);
  }
}

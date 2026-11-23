package pe.axiz.codegen.openapi;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonWriter;

/** JSON compacto y acotado para la salida del generador y las solicitudes de la CLI. */
public final class Json {
  private Json() {}

  public static String write(@Nullable Object value) {
    return JsonWriter.write(value);
  }
}

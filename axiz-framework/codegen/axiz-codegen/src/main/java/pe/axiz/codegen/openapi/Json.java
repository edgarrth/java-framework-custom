package pe.axiz.codegen.openapi;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonWriter;

/** Compact, bounded JSON for generator output and CLI requests. */
public final class Json {
  private Json() {}

  public static String write(@Nullable Object value) {
    return JsonWriter.write(value);
  }
}

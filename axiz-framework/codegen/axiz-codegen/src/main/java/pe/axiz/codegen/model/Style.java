package pe.axiz.codegen.model;

import java.util.List;

/**
 * Capacidades seleccionadas para un estilo arquitectónico, de modo que un servicio generado sea
 * coherente con lo que realmente arranca: sus migraciones, propiedades y recursos locales provienen
 * del mismo modelo que sus dependencias.
 *
 * @param capabilities capacidades que se agregan a cada servicio del estilo cuyo almacén las
 *     soporta (por ejemplo, inbox para los procesadores de eventos con persistencia)
 */
public record Style(List<String> capabilities) {

  /** Tablas de estructura que una migración puede crear: los parciales SQL del generador. */
  public static final List<String> TABLES =
      List.of("outbox", "inbox", "saga", "sequence", "shedlock");

  public static final Style NONE = new Style(List.of());

  public Style {
    capabilities = List.copyOf(capabilities);
  }
}

package pe.axiz.codegen.render;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.SafeYaml;

/**
 * Un conjunto de plantillas Mustache descrito por {@code axiz/templates/<set>/manifest.yaml}. Cada
 * entrada tiene una plantilla, una ruta de salida (que también es una plantilla), una condición
 * {@code when} opcional y una lista {@code each} opcional: la entrada se renderiza una vez por
 * elemento, disponible como {@code item}.
 */
public final class TemplateSet {

  /**
   * Una entrada del manifiesto.
   *
   * @param template archivo de plantilla, relativo al conjunto
   * @param path plantilla de la ruta de salida
   * @param when condición: rutas de contexto con puntos unidas con {@code &&} o {@code ||}; {@code
   *     !} niega
   * @param each ruta de contexto de una lista por iterar
   * @param binary copia el archivo de plantilla tal cual (sin renderizar)
   */
  public record Entry(
      String template, String path, @Nullable String when, @Nullable String each, boolean binary) {}

  private final String name;
  private final List<Entry> entries;
  private final Mustache.Compiler compiler;
  private final Map<String, Template> cache = new ConcurrentHashMap<>();

  private TemplateSet(String name, List<Entry> entries) {
    this.name = name;
    this.entries = List.copyOf(entries);
    this.compiler =
        Mustache.compiler()
            .escapeHTML(false)
            .nullValue("")
            .withLoader(partial -> reader("partials/" + partial + ".mustache"));
  }

  /** Carga un conjunto de plantillas desde el classpath. */
  public static TemplateSet load(String name) {
    Object doc;
    try (InputStream in = resource(name, "manifest.yaml")) {
      doc = SafeYaml.create(1_000_000).load(new InputStreamReader(in, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Entry> entries = new ArrayList<>();
    if (doc instanceof Map<?, ?> m && m.get("files") instanceof List<?> files) {
      for (Object f : files) {
        if (f instanceof Map<?, ?> e) {
          entries.add(
              new Entry(
                  String.valueOf(e.get("template")),
                  String.valueOf(e.get("path")),
                  e.get("when") == null ? null : String.valueOf(e.get("when")),
                  e.get("each") == null ? null : String.valueOf(e.get("each")),
                  Boolean.TRUE.equals(e.get("binary"))));
        }
      }
    }
    return new TemplateSet(name, entries);
  }

  public List<Entry> entries() {
    return entries;
  }

  /** Renderiza cada entrada aplicable; devuelve la ruta de salida con su contenido. */
  public Map<String, byte[]> render(Map<String, Object> context) {
    Map<String, byte[]> out = new LinkedHashMap<>();
    for (Entry e : entries) {
      String each = e.each();
      if (each == null) {
        if (Conditions.test(e.when(), context)) {
          out.put(renderString(e.path(), context), content(e, context));
        }
        continue;
      }
      for (Object item : Conditions.list(Conditions.resolve(each, context))) {
        Map<String, Object> scoped = new LinkedHashMap<>(context);
        scoped.put("item", item);
        if (Conditions.test(e.when(), scoped)) {
          out.put(renderString(e.path(), scoped), content(e, scoped));
        }
      }
    }
    return out;
  }

  /** Renderiza una plantilla del conjunto. */
  public String renderTemplate(String template, Map<String, Object> context) {
    return cache.computeIfAbsent(template, t -> compiler.compile(reader(t))).execute(context);
  }

  private byte[] content(Entry e, Map<String, Object> context) {
    if (e.binary()) {
      try (InputStream in = resource(name, e.template())) {
        return in.readAllBytes();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
    return renderTemplate(e.template(), context).getBytes(StandardCharsets.UTF_8);
  }

  private String renderString(String template, Map<String, Object> context) {
    return compiler.compile(new StringReader(template)).execute(context).strip();
  }

  private Reader reader(String template) {
    return new InputStreamReader(resource(name, template), StandardCharsets.UTF_8);
  }

  /**
   * Los archivos de plantilla son relativos al conjunto; un {@code /} inicial los hace relativos a
   * todos los conjuntos.
   */
  private static InputStream resource(String set, String file) {
    if (file.contains("..") || file.contains("\\")) {
      throw new IllegalArgumentException("Invalid template path " + file);
    }
    String path;
    if (set.equals("service") && file.matches("partials/sql/[a-z][a-z0-9-]*\\.mustache")) {
      String schema =
          file.substring("partials/sql/".length(), file.length() - ".mustache".length());
      path = "axiz/sql/" + schema + "-postgresql.sql";
    } else {
      path = file.startsWith("/") ? "axiz/templates" + file : "axiz/templates/" + set + "/" + file;
    }
    InputStream in = TemplateSet.class.getClassLoader().getResourceAsStream(path);
    return Objects.requireNonNull(in, () -> "Missing template " + path);
  }
}

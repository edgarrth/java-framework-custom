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
 * A set of Mustache templates described by {@code axiz/templates/<set>/manifest.yaml}. Each entry
 * has a template, an output path (itself a template), an optional {@code when} condition and an
 * optional {@code each} list: the entry is rendered once per element, available as {@code item}.
 */
public final class TemplateSet {

  /**
   * One manifest entry.
   *
   * @param template template file, relative to the set
   * @param path output path template
   * @param when condition: dotted context paths joined with {@code &&} or {@code ||}, {@code !}
   *     negates
   * @param each context path of a list to iterate
   * @param binary copy the template file as is (no rendering)
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

  /** Loads a template set from the classpath. */
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

  /** Renders every applicable entry; returns output path to content. */
  public Map<String, byte[]> render(Map<String, Object> context) {
    Map<String, byte[]> out = new LinkedHashMap<>();
    for (Entry e : entries) {
      if (e.each() == null) {
        if (Conditions.test(e.when(), context)) {
          out.put(renderString(e.path(), context), content(e, context));
        }
        continue;
      }
      for (Object item : Conditions.list(Conditions.resolve(e.each(), context))) {
        Map<String, Object> scoped = new LinkedHashMap<>(context);
        scoped.put("item", item);
        if (Conditions.test(e.when(), scoped)) {
          out.put(renderString(e.path(), scoped), content(e, scoped));
        }
      }
    }
    return out;
  }

  /** Renders one template of the set. */
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
   * Template files are relative to the set; a leading {@code /} makes them relative to all sets.
   */
  private static InputStream resource(String set, String file) {
    if (file.contains("..") || file.contains("\\")) {
      throw new IllegalArgumentException("Invalid template path " + file);
    }
    String path =
        file.startsWith("/") ? "axiz/templates" + file : "axiz/templates/" + set + "/" + file;
    InputStream in = TemplateSet.class.getClassLoader().getResourceAsStream(path);
    return Objects.requireNonNull(in, () -> "Missing template " + path);
  }
}

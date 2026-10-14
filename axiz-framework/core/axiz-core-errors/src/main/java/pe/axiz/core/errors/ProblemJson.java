package pe.axiz.core.errors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.json.JsonWriter;

/**
 * Serializes problems to {@code application/problem+json}. Used by every runtime (servlet,
 * reactive, security entry points) so responses are identical whatever produced them.
 */
public final class ProblemJson {

  private ProblemJson() {}

  public static Map<String, @Nullable Object> toMap(Problem p) {
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    m.put("type", p.type());
    m.put("title", p.title());
    m.put("status", p.status());
    m.put("detail", p.detail());
    if (p.instance() != null) {
      m.put("instance", p.instance());
    }
    m.put("code", p.code());
    if (p.correlationId() != null) {
      m.put("correlationId", p.correlationId());
    }
    m.put("timestamp", p.timestamp().toString());
    if (!p.violations().isEmpty()) {
      List<Map<String, @Nullable Object>> violations =
          p.violations().stream()
              .map(
                  v -> {
                    Map<String, @Nullable Object> vm = new LinkedHashMap<>();
                    vm.put("field", v.field());
                    vm.put("code", v.code());
                    vm.put("message", v.message());
                    if (v.rejectedValue() != null) {
                      vm.put("rejectedValue", v.rejectedValue());
                    }
                    return vm;
                  })
              .toList();
      m.put("violations", violations);
    }
    if (p.external() != null) {
      m.put("external", p.external());
    }
    return m;
  }

  public static String write(Problem p) {
    return JsonWriter.write(toMap(p));
  }
}

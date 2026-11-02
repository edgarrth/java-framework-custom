package pe.axiz.codegen.asyncapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.ContractDiff.Change;

/** Conservative evolution of published types: preserve vN and add optional payload fields only. */
public final class AsyncApiDiff {
  private static final Set<String> DOCUMENTATION =
      Set.of(
          "title",
          "description",
          "summary",
          "examples",
          "example",
          "$comment",
          "externalDocs",
          "tags");

  private AsyncApiDiff() {}

  public static List<Change> compare(AsyncApiDocument previous, AsyncApiDocument current) {
    List<Change> changes = new ArrayList<>();
    Map<String, AsyncApiDocument.Event> next = new LinkedHashMap<>();
    current.published().forEach(event -> next.put(event.type(), event));
    for (var event : previous.published()) {
      var updated = next.remove(event.type());
      if (updated == null)
        changes.add(
            new Change(
                true, event.type(), "Published type removed; retain vN during consumer migration"));
      else compareNode(event.message(), updated.message(), event.type(), false, changes);
    }
    next.keySet().forEach(type -> changes.add(new Change(false, type, "Published type added")));
    return List.copyOf(changes);
  }

  private static void compareNode(
      @Nullable Object previous,
      @Nullable Object current,
      String path,
      boolean schema,
      List<Change> changes) {
    if (Objects.equals(previous, current)) return;
    if (previous instanceof Map<?, ?> old && current instanceof Map<?, ?> next) {
      if (!schema) {
        for (Object key : union(old, next)) {
          if (DOCUMENTATION.contains(key)) continue;
          compareNode(
              old.get(key),
              next.get(key),
              path + "/" + key,
              key.equals("payload") || key.equals("headers"),
              changes);
        }
        return;
      }
      Map<?, ?> oldProperties = old.get("properties") instanceof Map<?, ?> p ? p : Map.of();
      Map<?, ?> newProperties = next.get("properties") instanceof Map<?, ?> p ? p : Map.of();
      List<?> oldRequired = old.get("required") instanceof List<?> list ? list : List.of();
      List<?> newRequired = next.get("required") instanceof List<?> list ? list : List.of();
      if (!Set.copyOf(oldRequired).equals(Set.copyOf(newRequired)))
        changes.add(
            new Change(
                true,
                path + "/required",
                "Required fields changed; publish a new event type version"));
      for (Object key : union(oldProperties, newProperties)) {
        String location = path + "/properties/" + key;
        if (!newProperties.containsKey(key))
          changes.add(new Change(true, location, "Payload field removed"));
        else if (!oldProperties.containsKey(key))
          changes.add(
              new Change(
                  newRequired.contains(key),
                  location,
                  newRequired.contains(key) ? "Required field added" : "Optional field added"));
        else compareNode(oldProperties.get(key), newProperties.get(key), location, true, changes);
      }
      for (Object key : union(old, next)) {
        if (DOCUMENTATION.contains(key) || key.equals("properties") || key.equals("required"))
          continue;
        if (key.equals("items"))
          compareNode(old.get(key), next.get(key), path + "/items", true, changes);
        else if (!Objects.equals(old.get(key), next.get(key)))
          changes.add(
              new Change(
                  true,
                  path + "/" + key,
                  "Schema or message semantics changed; publish a new event type version"));
      }
    } else
      changes.add(
          new Change(
              true, path, "Schema or message semantics changed; publish a new event type version"));
  }

  private static Set<Object> union(Map<?, ?> previous, Map<?, ?> current) {
    Set<Object> keys = new java.util.LinkedHashSet<>(previous.keySet());
    keys.addAll(current.keySet());
    return keys;
  }
}

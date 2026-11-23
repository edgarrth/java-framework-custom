package pe.axiz.codegen.asyncapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Snapshots;
import pe.axiz.codegen.openapi.SafeYaml;

/** Lector local de AsyncAPI 3 para CloudEvents versionados; nunca obtiene referencias externas. */
public final class AsyncApiDocument {
  public static final int MAX_SIZE = 2 * 1024 * 1024;

  /** El payload y los metadatos del mensaje para un tipo de CloudEvents. */
  public record Event(String type, Map<String, Object> message) {
    public Event {
      message = Snapshots.properties(message);
    }

    @Override
    public Map<String, Object> message() {
      return Snapshots.properties(message);
    }

    public Map<String, Object> payload() {
      return mapping(message.get("payload"));
    }
  }

  private final List<Event> published;
  private final List<Event> consumed;

  private AsyncApiDocument(List<Event> published, List<Event> consumed) {
    this.published = List.copyOf(published);
    this.consumed = List.copyOf(consumed);
  }

  public List<Event> published() {
    return published;
  }

  public List<Event> consumed() {
    return consumed;
  }

  public static AsyncApiDocument parse(String text) {
    try {
      return read(text);
    } catch (IllegalArgumentException problem) {
      throw problem;
    } catch (RuntimeException problem) {
      throw new IllegalArgumentException("Invalid AsyncAPI YAML: " + problem.getMessage(), problem);
    }
  }

  private static AsyncApiDocument read(String text) {
    if (text.length() > MAX_SIZE) throw new IllegalArgumentException("AsyncAPI contract too large");
    Map<String, Object> root = mapping(SafeYaml.create(MAX_SIZE).load(text));
    if (!String.valueOf(root.get("asyncapi")).matches("3\\.\\d+\\.\\d+"))
      throw new IllegalArgumentException("Only AsyncAPI 3.x contracts are supported");
    mapping(root.get("info"));
    Map<String, Event> published = new LinkedHashMap<>();
    Map<String, Event> consumed = new LinkedHashMap<>();
    Map<String, Event> definitions = new LinkedHashMap<>();
    int[] expansionBudget = {0};
    Map<String, Object> operations = optionalMap(root.get("operations"));
    for (Object rawOperation : operations.values()) {
      Map<String, Object> operation = resolve(root, mapping(rawOperation), new LinkedHashSet<>());
      if (operation.containsKey("traits"))
        throw new IllegalArgumentException("Flatten AsyncAPI operation traits before generation");
      String action = String.valueOf(operation.get("action"));
      if (!Set.of("send", "receive").contains(action))
        throw new IllegalArgumentException("AsyncAPI operation action must be send or receive");
      Map<String, Object> channelReference = mapping(operation.get("channel"));
      String channelRef = String.valueOf(channelReference.get("$ref"));
      if (!channelRef.startsWith("#/channels/") || channelRef.substring(11).contains("/"))
        throw new IllegalArgumentException("Operation must reference a local channel");
      Map<String, Object> channel = resolve(root, channelReference, new LinkedHashSet<>());
      Map<String, Object> messages = mapping(channel.get("messages"));
      List<Object> selected = new ArrayList<>();
      if (operation.containsKey("messages")) {
        if (!(operation.get("messages") instanceof List<?> list) || list.isEmpty())
          throw new IllegalArgumentException("Operation messages must be a nonempty list");
        for (Object item : list) {
          String ref = String.valueOf(mapping(item).get("$ref"));
          String prefix = channelRef + "/messages/";
          if (!ref.startsWith(prefix) || ref.substring(prefix.length()).contains("/"))
            throw new IllegalArgumentException("Operation message must belong to its channel");
          selected.add(resolve(root, mapping(item), new LinkedHashSet<>()));
        }
      } else selected.addAll(messages.values());
      if (selected.isEmpty()) throw new IllegalArgumentException("Channel has no messages");
      for (Object item : selected) {
        Map<String, Object> message = resolve(root, mapping(item), new LinkedHashSet<>());
        if (message.containsKey("traits"))
          throw new IllegalArgumentException("Flatten AsyncAPI message traits before generation");
        String type = String.valueOf(message.get("name"));
        if (!type.matches("[A-Za-z0-9][A-Za-z0-9_.-]*\\.v[1-9][0-9]*"))
          throw new IllegalArgumentException(
              "Message name must declare a CloudEvents type ending in .vN");
        Map<String, Object> expanded =
            mapping(expand(root, message, new LinkedHashSet<>(), expansionBudget, 0));
        Map<String, Object> payload = mapping(expanded.get("payload"));
        if (!"object".equals(payload.get("type")))
          throw new IllegalArgumentException(
              "CloudEvents payload must be an explicit JSON object: " + type);
        if (!expanded.containsKey("contentType"))
          expanded.put("contentType", root.getOrDefault("defaultContentType", "application/json"));
        Event event = new Event(type, expanded);
        Event prior = definitions.putIfAbsent(type, event);
        if (prior != null && !prior.equals(event))
          throw new IllegalArgumentException("Conflicting definitions of event type " + type);
        (action.equals("send") ? published : consumed).put(type, event);
      }
    }
    return new AsyncApiDocument(
        new ArrayList<>(published.values()), new ArrayList<>(consumed.values()));
  }

  private static Object expand(
      Map<String, Object> root,
      @Nullable Object value,
      Set<String> visiting,
      int[] count,
      int depth) {
    if (++count[0] > 100_000 || depth > 64)
      throw new IllegalArgumentException("AsyncAPI reference expansion limit exceeded");
    if (value instanceof Map<?, ?>) {
      Map<String, Object> node = mapping(value);
      if (node.get("$ref") instanceof String ref) {
        if (!visiting.add(ref))
          throw new IllegalArgumentException("Recursive AsyncAPI reference: " + ref);
        Object expanded = expand(root, reference(root, ref), visiting, count, depth + 1);
        visiting.remove(ref);
        if (node.size() != 1)
          throw new IllegalArgumentException(
              "Flatten reference siblings before generation: " + ref);
        return expanded;
      }
      Map<String, Object> result = new LinkedHashMap<>();
      node.forEach((key, item) -> result.put(key, expand(root, item, visiting, count, depth + 1)));
      return result;
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      list.forEach(item -> result.add(expand(root, item, visiting, count, depth + 1)));
      return result;
    }
    return value;
  }

  private static Map<String, Object> resolve(
      Map<String, Object> root, Map<String, Object> node, Set<String> visiting) {
    if (!(node.get("$ref") instanceof String ref)) return node;
    if (!visiting.add(ref) || visiting.size() > 64)
      throw new IllegalArgumentException("Cyclic AsyncAPI reference: " + ref);
    if (node.size() != 1)
      throw new IllegalArgumentException("Flatten reference siblings before generation: " + ref);
    return resolve(root, mapping(reference(root, ref)), visiting);
  }

  private static Object reference(Map<String, Object> root, String ref) {
    if (!ref.startsWith("#/"))
      throw new IllegalArgumentException("Only local AsyncAPI references are supported: " + ref);
    Object current = root;
    for (String part : ref.substring(2).split("/", -1)) {
      Map<String, Object> node = mapping(current);
      String key = part.replace("~1", "/").replace("~0", "~");
      if (!node.containsKey(key))
        throw new IllegalArgumentException("Unresolved AsyncAPI reference: " + ref);
      current = node.get(key);
    }
    if (current == null) throw new IllegalArgumentException("Null AsyncAPI reference: " + ref);
    return current;
  }

  private static Map<String, Object> optionalMap(@Nullable Object value) {
    return value == null ? Map.of() : mapping(value);
  }

  private static Map<String, Object> mapping(@Nullable Object value) {
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException("AsyncAPI object must be a mapping");
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach(
        (key, item) -> {
          if (!(key instanceof String text))
            throw new IllegalArgumentException("AsyncAPI mapping keys must be strings");
          result.put(text, item);
        });
    return result;
  }
}

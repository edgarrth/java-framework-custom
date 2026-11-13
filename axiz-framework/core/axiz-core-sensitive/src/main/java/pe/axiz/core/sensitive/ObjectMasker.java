package pe.axiz.core.sensitive;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Converts objects into masked, JSON-like structures (maps, lists, strings, numbers) for logs and
 * audit. Annotated fields ({@link Sensitive}, {@link Mask}) and well-known key names are masked by
 * the policy. Depth and collection sizes are bounded to protect logs.
 */
public final class ObjectMasker {

  private static final int MAX_DEPTH = 6;
  private static final int MAX_ITEMS = 50;
  private static final int MAX_STRING = 2000;

  private final MaskingPolicy policy;
  private final SensitiveKeys keys;
  private final Map<Class<?>, List<Property>> cache = new ConcurrentHashMap<>();

  public ObjectMasker(MaskingPolicy policy, SensitiveKeys keys) {
    this.policy = policy;
    this.keys = keys;
  }

  public static ObjectMasker defaults() {
    return new ObjectMasker(MaskingPolicy.defaults(), SensitiveKeys.defaults());
  }

  /**
   * Masked representation of any value for the given output.
   *
   * @param value object to describe
   * @param channel output channel
   * @return maps, lists, strings, numbers, booleans or null
   */
  public @Nullable Object describe(@Nullable Object value, OutputChannel channel) {
    return describe(value, channel, 0);
  }

  /**
   * Whether the values of a key are masked on the channel; a structured value (an array or an
   * object in JSON) under such a key is hidden whole.
   *
   * @param key property or header name
   * @param channel where the value goes
   * @return true for a sensitive key at the level the channel masks
   */
  public boolean masksKey(String key, OutputChannel channel) {
    Optional<SensitiveField> field = keys.find(key);
    return field.isPresent() && policy.masks(field.get().level(), channel);
  }

  public @Nullable String maskByKey(String key, @Nullable String value, OutputChannel channel) {
    Optional<SensitiveField> field = keys.find(key);
    return field.isPresent()
        ? policy.apply(value, field.get().level(), field.get().mask(), channel)
        : value;
  }

  private @Nullable Object describe(@Nullable Object value, OutputChannel channel, int depth) {
    if (value == null || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof CharSequence
        || value instanceof Enum<?>
        || value instanceof TemporalAccessor
        || value instanceof Character
        || value instanceof UUID) {
      String s = value.toString();
      return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) + "...(truncated)" : s;
    }
    if (depth >= MAX_DEPTH) {
      return "...";
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      int i = 0;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (i++ >= MAX_ITEMS) {
          out.put("...", (map.size() - MAX_ITEMS) + " more");
          break;
        }
        String k = String.valueOf(e.getKey());
        out.put(k, maskEntry(k, e.getValue(), channel, depth));
      }
      return out;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> out = new ArrayList<>();
      int i = 0;
      for (Object item : collection) {
        if (i++ >= MAX_ITEMS) {
          out.add("...(" + (collection.size() - MAX_ITEMS) + " more)");
          break;
        }
        out.add(describe(item, channel, depth + 1));
      }
      return out;
    }
    if (value instanceof byte[] bytes) {
      return "<" + bytes.length + " bytes>";
    }
    if (value.getClass().isArray()) {
      return "<array>";
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Property p : properties(value.getClass())) {
      Object raw = p.read(value);
      if (p.sensitive() != null) {
        SensitiveField sf = p.sensitive();
        out.put(
            p.name(),
            raw == null ? null : policy.apply(String.valueOf(raw), sf.level(), sf.mask(), channel));
      } else {
        out.put(p.name(), maskEntry(p.name(), raw, channel, depth));
      }
    }
    return out;
  }

  private @Nullable Object maskEntry(
      String key, @Nullable Object raw, OutputChannel channel, int depth) {
    Optional<SensitiveField> byKey = keys.find(key);
    if (byKey.isPresent() && raw != null && !(raw instanceof Map) && !(raw instanceof Collection)) {
      return policy.apply(String.valueOf(raw), byKey.get().level(), byKey.get().mask(), channel);
    }
    return describe(raw, channel, depth + 1);
  }

  private List<Property> properties(Class<?> type) {
    return cache.computeIfAbsent(type, ObjectMasker::introspect);
  }

  private static List<Property> introspect(Class<?> type) {
    List<Property> props = new ArrayList<>();
    if (type.isRecord()) {
      for (RecordComponent rc : type.getRecordComponents()) {
        props.add(
            new Property(
                rc.getName(),
                sensitivity(
                    rc.getName(), rc.getAnnotation(Sensitive.class), rc.getAnnotation(Mask.class)),
                rc,
                null));
      }
      return props;
    }
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      if (c.getName().startsWith("java.")) {
        break;
      }
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
          continue;
        }
        props.add(
            new Property(
                f.getName(),
                sensitivity(
                    f.getName(), f.getAnnotation(Sensitive.class), f.getAnnotation(Mask.class)),
                null,
                f));
      }
    }
    return props;
  }

  private static @Nullable SensitiveField sensitivity(
      String name, @Nullable Sensitive s, @Nullable Mask m) {
    if (s != null) {
      return SensitiveField.of(name, s);
    }
    return m != null ? SensitiveField.of(name, m) : null;
  }

  private record Property(
      String name,
      @Nullable SensitiveField sensitive,
      @Nullable RecordComponent component,
      @Nullable Field field) {

    @Nullable Object read(Object target) {
      try {
        if (component != null) {
          var accessor = component.getAccessor();
          accessor.setAccessible(true);
          return accessor.invoke(target);
        }
        if (field != null) {
          field.setAccessible(true);
          return field.get(target);
        }
        return null;
      } catch (ReflectiveOperationException | RuntimeException e) {
        return "<unreadable>";
      }
    }
  }
}

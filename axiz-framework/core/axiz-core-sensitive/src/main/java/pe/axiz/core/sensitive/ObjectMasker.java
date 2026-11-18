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
import java.util.Objects;
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

  private static final String UNREADABLE = "<unreadable>";
  private static final String COLLISION = "<collision>";
  private final MaskingPolicy policy;
  private final SensitiveKeys keys;
  private final boolean detectPan;
  private final Map<Class<?>, List<Property>> cache = new ConcurrentHashMap<>();

  public ObjectMasker(MaskingPolicy policy, SensitiveKeys keys, boolean detectPan) {
    this.policy = Objects.requireNonNull(policy);
    this.keys = Objects.requireNonNull(keys);
    this.detectPan = detectPan;
  }

  public static ObjectMasker defaults() {
    return new ObjectMasker(MaskingPolicy.defaults(), SensitiveKeys.defaults(), true);
  }

  /** Representation and number of unreadable or colliding values replaced with safe markers. */
  public record Description(@Nullable Object value, int sanitized) {}

  public @Nullable Object describe(@Nullable Object value, OutputChannel channel) {
    return describe(value, channel, 0, new Counter());
  }

  public @Nullable Object describeNamed(
      String name,
      @Nullable Object value,
      @Nullable SensitiveField declared,
      OutputChannel channel) {
    return describeCounting(name, value, declared, channel).value();
  }

  public Description describeCounting(
      String name,
      @Nullable Object value,
      @Nullable SensitiveField declared,
      OutputChannel channel) {
    Counter count = new Counter();
    Object result = named(name, value, declared, channel, 0, count);
    return new Description(result, count.sanitized);
  }

  public boolean masksKey(String key, OutputChannel channel) {
    return keys.find(key).map(f -> policy.masks(f.level(), channel)).orElse(false);
  }

  public @Nullable String maskByKey(String key, @Nullable String value, OutputChannel channel) {
    if (value == null) return null;
    Optional<SensitiveField> field = keys.find(key);
    String masked =
        field.isPresent()
            ? policy.apply(value, field.get().level(), field.get().mask(), channel)
            : value;
    return masked == null ? null : maskText(masked, channel);
  }

  /** PAN scanning for textual sinks; independent from key classification. */
  public String maskText(String value, OutputChannel channel) {
    return detectPan && channel != OutputChannel.RESPONSE ? PanDetector.redact(value) : value;
  }

  /** Exact supported number types only; unknown Number implementations are not evaluated. */
  public Object maskNumber(Number value, OutputChannel channel) {
    return detectPan && channel != OutputChannel.RESPONSE ? PanDetector.redactNumber(value) : value;
  }

  private @Nullable Object named(
      String name,
      @Nullable Object value,
      @Nullable SensitiveField declared,
      OutputChannel channel,
      int depth,
      Counter count) {
    try {
      if (value == null) return null;
      if (value instanceof SelfMasking self) return self.masked();
      SensitiveField field = declared != null ? declared : keys.find(name).orElse(null);
      if (field != null && policy.masks(field.level(), channel)) {
        return composite(value)
            ? Masker.FULL_MASK
            : policy.apply(String.valueOf(value), field.level(), field.mask(), channel);
      }
      return describe(value, channel, depth, count);
    } catch (RuntimeException error) {
      count.sanitized++;
      return UNREADABLE;
    }
  }

  private @Nullable Object describe(
      @Nullable Object value, OutputChannel channel, int depth, Counter count) {
    try {
      return describeValue(value, channel, depth, count);
    } catch (RuntimeException error) {
      count.sanitized++;
      return UNREADABLE;
    }
  }

  private @Nullable Object describeValue(
      @Nullable Object value, OutputChannel channel, int depth, Counter count) {
    if (value == null || value instanceof Boolean) return value;
    if (value instanceof SelfMasking self) return self.masked();
    if (value instanceof Number number) return maskNumber(number, channel);
    if (value instanceof CharSequence
        || value instanceof Enum<?>
        || value instanceof TemporalAccessor
        || value instanceof Character
        || value instanceof UUID) {
      String text = value.toString();
      text = text.length() > MAX_STRING ? text.substring(0, MAX_STRING) + "...(truncated)" : text;
      return maskText(text, channel);
    }
    if (depth >= MAX_DEPTH) return "...";
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      int i = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (i++ >= MAX_ITEMS) {
          out.put("...", "more items");
          break;
        }
        String key = String.valueOf(entry.getKey());
        if (out.containsKey(key)) {
          out.put(key, COLLISION);
          count.sanitized++;
        } else out.put(key, named(key, entry.getValue(), null, channel, depth + 1, count));
      }
      return out;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> out = new ArrayList<>();
      int i = 0;
      for (Object item : collection) {
        if (i++ >= MAX_ITEMS) {
          out.add("...(more items)");
          break;
        }
        out.add(describe(item, channel, depth + 1, count));
      }
      return out;
    }
    if (value instanceof byte[] bytes) return "<" + bytes.length + " bytes>";
    if (value.getClass().isArray()) return "<array>";
    Map<String, Object> out = new LinkedHashMap<>();
    for (Property property : properties(value.getClass())) {
      if (out.size() >= MAX_ITEMS) {
        out.put("...", "more properties");
        break;
      }
      if (out.containsKey(property.name())) {
        out.put(property.name(), COLLISION);
        count.sanitized++;
      } else
        out.put(
            property.name(),
            named(
                property.name(),
                property.read(value),
                property.sensitive(),
                channel,
                depth + 1,
                count));
    }
    return out;
  }

  private static boolean composite(Object value) {
    return value instanceof Map || value instanceof Collection || value.getClass().isArray();
  }

  private static final class Counter {
    private int sanitized;
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
        throw new IllegalStateException("Property cannot be inspected", e);
      }
    }
  }
}

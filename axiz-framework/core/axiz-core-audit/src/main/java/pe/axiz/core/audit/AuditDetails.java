package pe.axiz.core.audit;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonWriter;

/** Bounded, deeply immutable snapshots. Unknown values are never stringified. */
final class AuditDetails {
  static final int MAX_NODES = 500;
  static final int MAX_BYTES = 32 * 1024;
  static final int MAX_KEY = 128;
  static final int MAX_TEXT = 2000;
  static final int MAX_DEPTH = 6;
  static final String UNSUPPORTED = "<unsupported>";
  static final String TRUNCATED = "<truncated>";
  static final String CYCLE = "<cycle>";
  private static final int MARKER_BYTES = 15;

  record Frozen(Map<String, @Nullable Object> details, int sanitized) {}

  private int nodes = 1;
  private int bytes = 2;
  private int sanitized;
  private int visited;
  private final Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());

  private AuditDetails() {}

  static Frozen freeze(Map<String, ?> source) {
    Objects.requireNonNull(source, "details");
    if (source instanceof FrozenMap frozen) return new Frozen(frozen, 0);
    var freezer = new AuditDetails();
    freezer.path.add(source);
    try {
      var details = freezer.map(source, 0);
      return new Frozen(details, freezer.sanitized);
    } catch (RuntimeException failure) {
      // A hostile root map cannot produce a scalar: discard that entire subtree.
      return new Frozen(new FrozenMap(Map.of()), 1);
    }
  }

  private boolean admits(int punctuation) {
    return nodes < MAX_NODES && bytes + punctuation + MARKER_BYTES <= MAX_BYTES;
  }

  private FrozenMap map(Map<?, ?> source, int depth) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    for (var entry : source.entrySet()) {
      // Invalid keys must not allow an unbounded scan that spends no output budget.
      if (++visited > MAX_NODES) {
        sanitized++;
        break;
      }
      if (!(entry.getKey() instanceof String key)
          || key.length() > MAX_KEY
          || out.containsKey(key)) {
        sanitized++;
        continue;
      }
      int punctuation = JsonWriter.escapedUtf8Length(key) + 3 + (out.isEmpty() ? 0 : 1);
      if (!admits(punctuation)) {
        sanitized++;
        break;
      }
      bytes += punctuation;
      out.put(key, value(entry.getValue(), depth + 1));
    }
    return new FrozenMap(out);
  }

  private FrozenList list(Iterator<?> source, int depth) {
    List<@Nullable Object> out = new ArrayList<>();
    while (source.hasNext()) {
      int punctuation = out.isEmpty() ? 0 : 1;
      if (!admits(punctuation)) {
        sanitized++;
        break;
      }
      bytes += punctuation;
      if (++visited > MAX_NODES) {
        out.add(marker(TRUNCATED));
        break;
      }
      out.add(value(source.next(), depth + 1));
    }
    return new FrozenList(out);
  }

  private @Nullable Object value(@Nullable Object raw, int depth) {
    int nodesBefore = nodes;
    int bytesBefore = bytes;
    int sanitizedBefore = sanitized;
    try {
      if (raw == null) return leaf(null, 0);
      Class<?> type = raw.getClass();
      if (type == String.class) return text((String) raw);
      if (type == Boolean.class
          || type == Integer.class
          || type == Long.class
          || type == Short.class
          || type == Byte.class) return leaf(raw, 0);
      if (type == Double.class)
        return Double.isFinite((Double) raw) ? leaf(raw, 0) : marker(UNSUPPORTED);
      if (type == Float.class)
        return Float.isFinite((Float) raw) ? leaf(raw, 0) : marker(UNSUPPORTED);
      if (type == BigInteger.class)
        return ((BigInteger) raw).bitLength() <= 256 ? leaf(raw, 0) : marker(UNSUPPORTED);
      if (type == BigDecimal.class) {
        var decimal = (BigDecimal) raw;
        return decimal.precision() <= 64 && decimal.scale() >= -64 && decimal.scale() <= 64
            ? leaf(raw, 0)
            : marker(UNSUPPORTED);
      }
      if (type == AtomicInteger.class) return leaf(((AtomicInteger) raw).get(), 0);
      if (type == AtomicLong.class) return leaf(((AtomicLong) raw).get(), 0);
      if (type == LongAdder.class) return leaf(((LongAdder) raw).sum(), 0);
      if (raw instanceof Enum<?> enumeration) return text(enumeration.name());
      if (type == Character.class
          || type == UUID.class
          || type == Instant.class
          || type == LocalDate.class
          || type == LocalDateTime.class
          || type == OffsetDateTime.class
          || type == ZonedDateTime.class
          || type == Duration.class) return text(raw.toString());
      if (!(raw instanceof Map<?, ?>) && !(raw instanceof Collection<?>) && !type.isArray())
        return marker(UNSUPPORTED);
      if (depth >= MAX_DEPTH) return marker(TRUNCATED);
      if (!path.add(raw)) return marker(CYCLE);
      try {
        nodes++;
        bytes += 2;
        if (raw instanceof Map<?, ?> map) return map(map, depth);
        if (raw instanceof Collection<?> collection) return list(collection.iterator(), depth);
        int length = Array.getLength(raw);
        return list(
            new Iterator<>() {
              private int index;

              @Override
              public boolean hasNext() {
                return index < length;
              }

              @Override
              public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return Array.get(raw, index++);
              }
            },
            depth);
      } finally {
        path.remove(raw);
      }
    } catch (RuntimeException failure) {
      nodes = nodesBefore;
      bytes = bytesBefore;
      sanitized = sanitizedBefore;
      return marker(UNSUPPORTED);
    }
  }

  private @Nullable Object text(String raw) {
    if (raw.length() <= MAX_TEXT) return leaf(raw, 0);
    int end = MAX_TEXT;
    if (Character.isHighSurrogate(raw.charAt(end - 1)) && Character.isLowSurrogate(raw.charAt(end)))
      end--;
    return leaf(raw.substring(0, end), 1);
  }

  private @Nullable Object leaf(@Nullable Object scalar, int replacements) {
    int cost = JsonWriter.canonicalLength(scalar);
    if (nodes < MAX_NODES && bytes + cost <= MAX_BYTES) {
      nodes++;
      bytes += cost;
      sanitized += replacements;
      return scalar;
    }
    return marker(TRUNCATED);
  }

  private String marker(String value) {
    sanitized++;
    nodes++;
    bytes += JsonWriter.canonicalLength(value);
    return value;
  }

  private static final class FrozenMap extends AbstractMap<String, @Nullable Object> {
    private final Map<String, @Nullable Object> values;

    private FrozenMap(Map<String, @Nullable Object> values) {
      this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public Set<Entry<String, @Nullable Object>> entrySet() {
      return values.entrySet();
    }
  }

  private static final class FrozenList extends AbstractList<@Nullable Object>
      implements RandomAccess {
    private final List<@Nullable Object> values;

    private FrozenList(List<@Nullable Object> values) {
      this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    public @Nullable Object get(int index) {
      return values.get(index);
    }

    @Override
    public int size() {
      return values.size();
    }
  }
}

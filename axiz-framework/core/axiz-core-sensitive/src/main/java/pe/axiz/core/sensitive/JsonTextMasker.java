package pe.axiz.core.sensitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.json.JsonWriter;

/**
 * Masks bounded RFC 8259 documents using the shared strict JSON parser and writer. Escapes,
 * whitespace and numeric notation are canonicalized; semantic values are retained unless masked.
 * Invalid or incomplete input never falls back to the original body.
 */
public final class JsonTextMasker {
  public static final int MAX_DEPTH = JsonReader.MAX_DEPTH;
  public static final String UNREADABLE = "<not a complete JSON value: not logged>";
  private final ObjectMasker masker;

  public JsonTextMasker(ObjectMasker masker) {
    this.masker = Objects.requireNonNull(masker);
  }

  public String mask(String json, OutputChannel channel) {
    return tryMask(json, channel).orElse(UNREADABLE);
  }

  public Optional<String> tryMask(String json, OutputChannel channel) {
    try {
      return Optional.of(JsonWriter.write(transform(JsonReader.parse(json), null, channel)));
    } catch (RuntimeException malformed) {
      return Optional.empty();
    }
  }

  private @Nullable Object transform(
      @Nullable Object value, @Nullable String key, OutputChannel channel) {
    if (value == null) return null;
    if (key != null && masker.masksKey(key, channel)) {
      return value instanceof Map || value instanceof List
          ? Masker.FULL_MASK
          : masker.maskByKey(key, String.valueOf(value), channel);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, @Nullable Object> result = new LinkedHashMap<>();
      map.forEach(
          (name, entry) -> result.put((String) name, transform(entry, (String) name, channel)));
      return result;
    }
    if (value instanceof List<?> values) {
      List<@Nullable Object> result = new ArrayList<>(values.size());
      for (Object element : values) result.add(transform(element, null, channel));
      return result;
    }
    if (value instanceof String text) return masker.maskText(text, channel);
    if (value instanceof Number number) return masker.maskNumber(number, channel);
    return value;
  }
}

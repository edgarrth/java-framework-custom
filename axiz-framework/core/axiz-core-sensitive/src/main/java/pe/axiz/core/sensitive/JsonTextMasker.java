package pe.axiz.core.sensitive;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks values of sensitive keys inside raw JSON text (HTTP bodies in logs) without parsing it.
 * Handles string, number and boolean values; nested objects are traversed because every key is
 * matched wherever it appears.
 */
public final class JsonTextMasker {

  private static final Pattern PAIR =
      Pattern.compile(
          "\"([^\"\\\\]{1,100})\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|true|false)");

  private final ObjectMasker masker;

  public JsonTextMasker(ObjectMasker masker) {
    this.masker = masker;
  }

  public String mask(String json, OutputChannel channel) {
    Matcher m = PAIR.matcher(json);
    StringBuilder out = new StringBuilder(json.length());
    while (m.find()) {
      String key = m.group(1);
      String rawValue = m.group(2);
      boolean quoted = rawValue.startsWith("\"");
      String value = quoted ? rawValue.substring(1, rawValue.length() - 1) : rawValue;
      String masked = masker.maskByKey(key, value, channel);
      String replacement =
          masked == null || masked.equals(value) ? m.group() : "\"" + key + "\":\"" + masked + "\"";
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }
}

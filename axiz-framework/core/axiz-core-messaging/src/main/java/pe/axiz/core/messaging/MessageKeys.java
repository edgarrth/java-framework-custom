package pe.axiz.core.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identity of a message for deduplication. CloudEvents makes an event unique by {@code source}
 * <b>and</b> {@code id}: two producers may legitimately use the same id, so the key carries both.
 * The source length is part of the key, so no pair of different (source, id) values produces the
 * same key; keys longer than {@link #MAX_LENGTH} are replaced by a SHA-256 digest of the pair.
 */
public final class MessageKeys {

  /** Longest key stored as is (column {@code message_id} of the inbox is 128). */
  public static final int MAX_LENGTH = 128;

  private MessageKeys() {}

  public static String of(EventEnvelope envelope) {
    return of(envelope.source(), envelope.id());
  }

  /**
   * Key of a message from its source and id (an id is only unique within its source).
   *
   * @param source CloudEvents source
   * @param id CloudEvents id
   * @return {@code <source length>:<source>|<id>}, or {@code sha256:<hex>} when longer
   */
  public static String of(String source, String id) {
    String key = source.length() + ":" + source + "|" + id;
    if (key.length() <= MAX_LENGTH) {
      return key;
    }
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      d.update(source.getBytes(StandardCharsets.UTF_8));
      d.update((byte) 0);
      d.update(id.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(d.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

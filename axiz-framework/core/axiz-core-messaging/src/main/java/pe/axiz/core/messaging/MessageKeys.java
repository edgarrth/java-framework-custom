package pe.axiz.core.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identidad de un mensaje para la deduplicación. CloudEvents hace único a un evento por {@code
 * source} <b>e</b> {@code id}: dos productores pueden usar legítimamente el mismo id, así que la
 * clave lleva ambos. La longitud del source forma parte de la clave, de modo que ningún par de
 * valores (source, id) distintos produce la misma clave; las claves más largas que {@link
 * #MAX_LENGTH} se reemplazan por un digest SHA-256 del par.
 */
public final class MessageKeys {

  /**
   * Clave más larga que se almacena tal cual (la columna {@code message_id} del inbox es de 128).
   */
  public static final int MAX_LENGTH = 128;

  private MessageKeys() {}

  public static String of(EventEnvelope envelope) {
    return of(envelope.source(), envelope.id());
  }

  /**
   * Clave de un mensaje a partir de su source e id (un id solo es único dentro de su source).
   *
   * @param source source de CloudEvents
   * @param id id de CloudEvents
   * @return {@code <source length>:<source>|<id>}, o {@code sha256:<hex>} cuando es más larga
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

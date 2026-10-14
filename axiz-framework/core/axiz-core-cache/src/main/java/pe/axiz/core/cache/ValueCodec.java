package pe.axiz.core.cache;

/** Serializes cached values (JSON with the service mapper in the starter). */
public interface ValueCodec {

  byte[] encode(Object value);

  <T> T decode(byte[] bytes, Class<T> type);
}

package pe.axiz.core.cache;

/** Serializa los valores en caché (JSON con el mapper del servicio en el starter). */
public interface ValueCodec {

  byte[] encode(Object value);

  <T> T decode(byte[] bytes, Class<T> type);
}

package pe.axiz.core.messaging;

/** Encodes and decodes event payloads (JSON with the service's mapper in the starter). */
public interface PayloadCodec {

  String contentType();

  byte[] encode(Object payload);

  <T> T decode(byte[] data, Class<T> type);
}

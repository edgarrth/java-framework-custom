package pe.axiz.core.messaging;

/** Codifica y decodifica payloads de eventos (JSON con el mapper del servicio en el starter). */
public interface PayloadCodec {

  String contentType();

  byte[] encode(Object payload);

  <T> T decode(byte[] data, Class<T> type);
}

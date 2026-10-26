package pe.axiz.payments.beneficiaries.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import pe.axiz.core.cache.ValueCodec;

/** Keeps values by reference: enough to test the cache flow without a JSON codec. */
public final class ReferenceCodec implements ValueCodec {

  private final Map<String, Object> values = new ConcurrentHashMap<>();

  @Override
  public byte[] encode(Object value) {
    String id = UUID.randomUUID().toString();
    values.put(id, value);
    return id.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public <T> T decode(byte[] bytes, Class<T> type) {
    return type.cast(values.get(new String(bytes, StandardCharsets.UTF_8)));
  }
}

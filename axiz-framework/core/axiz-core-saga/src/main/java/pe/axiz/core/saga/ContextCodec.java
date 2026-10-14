package pe.axiz.core.saga;

/** Serializes saga contexts (JSON with the service mapper in the starter). */
public interface ContextCodec<C> {

  String encode(C context);

  C decode(String text);
}

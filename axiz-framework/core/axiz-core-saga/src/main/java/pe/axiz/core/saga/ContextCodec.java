package pe.axiz.core.saga;

/** Serializa los contextos de saga (JSON con el mapper del servicio en el starter). */
public interface ContextCodec<C> {

  String encode(C context);

  C decode(String text);
}

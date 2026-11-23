package pe.axiz.structure.bff;

/**
 * Da forma a un resultado compuesto para un canal (móvil, web, socio, etc.).
 *
 * @param <S> fuente compuesta
 * @param <T> respuesta del canal
 */
public interface ChannelAdapter<S, T> {

  /** Canal atendido, por ejemplo {@code mobile}; {@code *} es el adaptador por defecto. */
  String channel();

  T adapt(S source);
}

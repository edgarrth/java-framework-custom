package pe.axiz.framework.bff;

/**
 * Shapes a composed result for one channel (mobile, web, partner...).
 *
 * @param <S> composed source
 * @param <T> channel response
 */
public interface ChannelAdapter<S, T> {

  /** Channel served, e.g. {@code mobile}; {@code *} is the default adapter. */
  String channel();

  T adapt(S source);
}

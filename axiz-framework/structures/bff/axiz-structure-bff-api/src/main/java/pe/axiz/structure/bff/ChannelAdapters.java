package pe.axiz.structure.bff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Elige el adaptador del canal de quien llama y, en su defecto, el adaptador por defecto ({@code
 * *}).
 *
 * @param <S> fuente compuesta
 * @param <T> respuesta del canal
 */
public final class ChannelAdapters<S, T> {

  public static final String DEFAULT = "*";

  private final Map<String, ChannelAdapter<S, T>> byChannel = new HashMap<>();

  public ChannelAdapters(List<? extends ChannelAdapter<S, T>> adapters) {
    for (ChannelAdapter<S, T> a : adapters) {
      if (byChannel.put(a.channel(), a) != null) {
        throw new IllegalStateException("Two adapters for channel " + a.channel());
      }
    }
  }

  public T adapt(S source, @Nullable String channel) {
    ChannelAdapter<S, T> a = channel != null ? byChannel.get(channel) : null;
    if (a == null) {
      a = byChannel.get(DEFAULT);
    }
    if (a == null) {
      throw new IllegalStateException(
          "No adapter for channel " + channel + " and no default adapter");
    }
    return a.adapt(source);
  }
}

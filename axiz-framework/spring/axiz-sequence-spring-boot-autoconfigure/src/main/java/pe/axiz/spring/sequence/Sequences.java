package pe.axiz.spring.sequence;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import pe.axiz.core.sequence.SequenceFormat;
import pe.axiz.core.sequence.SequenceGenerator;
import pe.axiz.core.sequence.SequenceStore;

/** Generadores para las secuencias configuradas; los nombres desconocidos fallan de inmediato. */
public final class Sequences {

  private final AxizSequenceProperties properties;
  private final SequenceStore store;
  private final Clock clock;
  private final Map<String, SequenceGenerator> generators = new ConcurrentHashMap<>();

  public Sequences(AxizSequenceProperties properties, SequenceStore store, Clock clock) {
    this.properties = Objects.requireNonNull(properties);
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
    properties.sequences().keySet().forEach(this::generator);
  }

  public SequenceGenerator generator(String name) {
    return generators.computeIfAbsent(
        name,
        n -> {
          AxizSequenceProperties.Spec s = properties.sequences().get(n);
          if (s == null) {
            throw new IllegalArgumentException(
                "Sequence " + n + " is not configured under axiz.sequence.sequences");
          }
          return new SequenceGenerator(
              n,
              new SequenceFormat(s.prefix(), s.width(), s.reset(), s.separator(), s.zone()),
              store,
              s.blockSize(),
              clock);
        });
  }

  /** Devuelve el siguiente valor formateado de una secuencia. */
  public String next(String name) {
    return generator(name).next();
  }
}

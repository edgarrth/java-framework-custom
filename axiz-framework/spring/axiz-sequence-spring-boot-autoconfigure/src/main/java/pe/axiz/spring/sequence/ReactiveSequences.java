package pe.axiz.spring.sequence;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import pe.axiz.core.sequence.AsyncSequenceGenerator;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.core.sequence.SequenceFormat;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/** Deferred Reactor facade over native asynchronous sequence allocation. */
public final class ReactiveSequences {
  private final Map<String, AsyncSequenceGenerator> generators = new ConcurrentHashMap<>();

  public ReactiveSequences(
      AxizSequenceProperties properties, AsyncSequenceStore store, Clock clock) {
    Objects.requireNonNull(properties);
    Objects.requireNonNull(store);
    Objects.requireNonNull(clock);
    properties
        .sequences()
        .forEach(
            (name, spec) ->
                generators.put(
                    name,
                    new AsyncSequenceGenerator(
                        name,
                        new SequenceFormat(
                            spec.prefix(),
                            spec.width(),
                            spec.reset(),
                            spec.separator(),
                            spec.zone()),
                        store,
                        spec.blockSize(),
                        clock)));
  }

  public Mono<String> next(String name) {
    return Mono.defer(
        () -> {
          AsyncSequenceGenerator generator = generators.get(name);
          if (generator == null)
            return Mono.error(
                new IllegalArgumentException(
                    "Sequence " + name + " is not configured under axiz.sequence.sequences"));
          return ReactiveStages.from(generator::next);
        });
  }
}

package pe.axiz.core.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Optional catalog entry for an event: fixes its category, the unit of its value and the histogram
 * buckets (SLOs) of its distributions. Events work without a definition.
 *
 * @param name event name
 * @param category category
 * @param valueUnit unit of {@code axiz.events.value} for this event (records, items, points...)
 * @param durationSlos duration buckets
 * @param amountSlos amount buckets
 * @param valueSlos value buckets
 */
public record EventDefinition(
    String name,
    @Nullable String category,
    @Nullable String valueUnit,
    List<Duration> durationSlos,
    List<Double> amountSlos,
    List<Double> valueSlos) {

  public EventDefinition {
    Objects.requireNonNull(name);
    durationSlos = List.copyOf(durationSlos);
    amountSlos = List.copyOf(amountSlos);
    valueSlos = List.copyOf(valueSlos);
  }

  public static EventDefinition of(String name, String category) {
    return new EventDefinition(name, category, null, List.of(), List.of(), List.of());
  }
}

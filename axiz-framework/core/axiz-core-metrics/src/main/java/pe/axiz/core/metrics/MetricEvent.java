package pe.axiz.core.metrics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;

/**
 * Description of one occurrence of an event. One {@link EventMetrics#record(MetricEvent)} call
 * counts it and, depending on what is present, sums its amount and value and times its duration.
 *
 * <pre>{@code
 * metrics.record(MetricEvent.of("payment").outcome(Outcome.SUCCESS).channel("web")
 *     .product("credit-card").amount(Money.of("150.00", "PEN")).duration(elapsed)
 *     .tag("card-brand", "visa"));
 * }</pre>
 *
 * <p>Mutable and not thread-safe: build one per occurrence.
 */
public final class MetricEvent {

  private final String event;
  private @Nullable String category;
  private @Nullable Outcome outcome;
  private @Nullable String reason;
  private @Nullable String channel;
  private @Nullable String product;
  private @Nullable String stage;
  private @Nullable String tenant;
  private @Nullable Money amount;
  private @Nullable Double value;
  private @Nullable Duration duration;
  private final Map<String, String> tags = new LinkedHashMap<>();

  private MetricEvent(String event) {
    this.event = event;
  }

  public static MetricEvent of(String event) {
    return new MetricEvent(event);
  }

  public MetricEvent category(@Nullable String value) {
    this.category = value;
    return this;
  }

  public MetricEvent outcome(@Nullable Outcome value) {
    this.outcome = value;
    return this;
  }

  public MetricEvent reason(@Nullable String value) {
    this.reason = value;
    return this;
  }

  public MetricEvent channel(@Nullable String value) {
    this.channel = value;
    return this;
  }

  public MetricEvent product(@Nullable String value) {
    this.product = value;
    return this;
  }

  public MetricEvent stage(@Nullable String value) {
    this.stage = value;
    return this;
  }

  public MetricEvent tenant(@Nullable String value) {
    this.tenant = value;
    return this;
  }

  public MetricEvent amount(@Nullable Money value) {
    this.amount = value;
    return this;
  }

  public MetricEvent value(double v) {
    this.value = v;
    return this;
  }

  public MetricEvent duration(@Nullable Duration value) {
    this.duration = value;
    return this;
  }

  /** Custom tag; it must be declared in the metrics configuration or it is dropped. */
  public MetricEvent tag(String key, @Nullable String tagValue) {
    if (tagValue != null) {
      tags.put(key, tagValue);
    }
    return this;
  }

  public String event() {
    return event;
  }

  public @Nullable String category() {
    return category;
  }

  public @Nullable Outcome outcome() {
    return outcome;
  }

  public @Nullable String reason() {
    return reason;
  }

  public @Nullable String channel() {
    return channel;
  }

  public @Nullable String product() {
    return product;
  }

  public @Nullable String stage() {
    return stage;
  }

  public @Nullable String tenant() {
    return tenant;
  }

  public @Nullable Money amount() {
    return amount;
  }

  public @Nullable Double value() {
    return value;
  }

  public @Nullable Duration duration() {
    return duration;
  }

  public Map<String, String> tags() {
    return Map.copyOf(tags);
  }
}

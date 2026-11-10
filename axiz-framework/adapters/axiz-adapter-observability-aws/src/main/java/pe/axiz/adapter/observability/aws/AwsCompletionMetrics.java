package pe.axiz.adapter.observability.aws;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Optional metrics binding; owners with the same stable pool name share aggregate gauges. */
public final class AwsCompletionMetrics {
  private static final Map<MeterRegistry, Map<String, Group>> GROUPS = new IdentityHashMap<>();

  private AwsCompletionMetrics() {}

  /** Creates an owner whose completion metrics belong to this application's registry. */
  public static AsyncAwsClients create(
      String name, int workers, int queueCapacity, MeterRegistry registry) {
    requireName(name);
    Objects.requireNonNull(registry, "registry");
    var owner = AsyncAwsClients.unmetered(workers, queueCapacity);
    try {
      bind(owner, name, registry);
      return owner;
    } catch (RuntimeException | Error failure) {
      owner.close();
      throw failure;
    }
  }

  /** Replaces an owner's optional metrics binding; the owner closes it with its resources. */
  public static void bind(AsyncAwsClients owner, String name, MeterRegistry registry) {
    requireName(name);
    Objects.requireNonNull(registry, "registry");
    owner.replaceCompletionMetrics(executor -> bind(name, executor, registry));
  }

  private static void requireName(String name) {
    if (name.isBlank()) throw new IllegalArgumentException("poolName must not be blank");
  }

  static Runnable bind(String name, BoundedCompletionExecutor executor) {
    return bind(name, executor, Metrics.globalRegistry);
  }

  static synchronized Runnable bind(
      String name, BoundedCompletionExecutor executor, MeterRegistry registry) {
    Map<String, Group> groups = GROUPS.computeIfAbsent(registry, ignored -> new HashMap<>());
    Group group = groups.computeIfAbsent(name, ignored -> new Group(name, registry));
    group.executors.add(executor);
    executor.observe(group);
    return () -> release(name, executor, registry, group);
  }

  private static synchronized void release(
      String name, BoundedCompletionExecutor executor, MeterRegistry registry, Group group) {
    group.executors.remove(executor);
    Map<String, Group> groups = GROUPS.get(registry);
    if (groups != null && group.executors.isEmpty() && groups.remove(name, group)) {
      group.meters.forEach(registry::remove);
      if (groups.isEmpty()) GROUPS.remove(registry);
    }
  }

  private static final class Group implements BoundedCompletionExecutor.Observer {
    final Set<BoundedCompletionExecutor> executors = ConcurrentHashMap.newKeySet();
    final List<Meter> meters;
    final Counter rejected;
    final Timer latency;

    Group(String name, MeterRegistry registry) {
      var pending =
          Gauge.builder(
                  "axiz.transport.pending",
                  this,
                  g ->
                      g.executors.stream()
                          .mapToInt(e -> e.getActiveCount() + e.getQueue().size())
                          .sum())
              .tag("transport", name)
              .register(registry);
      var queued =
          Gauge.builder(
                  "axiz.transport.queued",
                  this,
                  g -> g.executors.stream().mapToInt(e -> e.getQueue().size()).sum())
              .tag("transport", name)
              .register(registry);
      var active =
          Gauge.builder(
                  "axiz.transport.active",
                  this,
                  g ->
                      g.executors.stream()
                          .mapToInt(BoundedCompletionExecutor::getActiveCount)
                          .sum())
              .tag("transport", name)
              .register(registry);
      rejected = registry.counter("axiz.transport.rejected", "transport", name);
      latency = registry.timer("axiz.transport.latency", "transport", name);
      meters = List.of(pending, queued, active, rejected, latency);
    }

    @Override
    public void rejected() {
      rejected.increment();
    }

    @Override
    public void completed(long nanos) {
      latency.record(nanos, TimeUnit.NANOSECONDS);
    }
  }
}

package pe.axiz.adapter.observability.aws;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Optional metrics binding; owners with the same stable pool name share aggregate gauges. */
final class AwsCompletionMetrics {
  private static final Map<String, Group> GROUPS = new HashMap<>();

  private AwsCompletionMetrics() {}

  static synchronized Runnable bind(String name, BoundedCompletionExecutor executor) {
    Group group = GROUPS.computeIfAbsent(name, Group::new);
    group.executors.add(executor);
    executor.observe(group);
    return () -> release(name, executor, group);
  }

  private static synchronized void release(
      String name, BoundedCompletionExecutor executor, Group group) {
    group.executors.remove(executor);
    if (group.executors.isEmpty() && GROUPS.remove(name, group))
      group.meters.forEach(Metrics.globalRegistry::remove);
  }

  private static final class Group implements BoundedCompletionExecutor.Observer {
    final Set<BoundedCompletionExecutor> executors = ConcurrentHashMap.newKeySet();
    final List<Meter> meters;
    final Counter rejected;
    final Timer latency;

    Group(String name) {
      var pending =
          Gauge.builder(
                  "axiz.transport.pending",
                  this,
                  g ->
                      g.executors.stream()
                          .mapToInt(e -> e.getActiveCount() + e.getQueue().size())
                          .sum())
              .tag("transport", name)
              .register(Metrics.globalRegistry);
      var queued =
          Gauge.builder(
                  "axiz.transport.queued",
                  this,
                  g -> g.executors.stream().mapToInt(e -> e.getQueue().size()).sum())
              .tag("transport", name)
              .register(Metrics.globalRegistry);
      var active =
          Gauge.builder(
                  "axiz.transport.active",
                  this,
                  g ->
                      g.executors.stream()
                          .mapToInt(BoundedCompletionExecutor::getActiveCount)
                          .sum())
              .tag("transport", name)
              .register(Metrics.globalRegistry);
      rejected = Metrics.globalRegistry.counter("axiz.transport.rejected", "transport", name);
      latency = Metrics.globalRegistry.timer("axiz.transport.latency", "transport", name);
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

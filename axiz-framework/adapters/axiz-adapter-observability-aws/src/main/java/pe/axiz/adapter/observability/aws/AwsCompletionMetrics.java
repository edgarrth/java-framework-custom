package pe.axiz.adapter.observability.aws;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.aws.transport.CompletionObserver;
import pe.axiz.adapter.aws.transport.CompletionPool;
import pe.axiz.core.commons.net.TransportSecurityPolicy;

/** Optional metrics binding; owners with the same stable pool name share aggregate gauges. */
public final class AwsCompletionMetrics {
  private static final IdentityHashMap<MeterRegistry, Map<String, Group>> GROUPS =
      new IdentityHashMap<>();

  private AwsCompletionMetrics() {}

  /** Creates an owner whose completion metrics belong to this application's registry. */
  public static AsyncAwsClients create(
      String name, int workers, int queueCapacity, MeterRegistry registry) {
    requireName(name);
    Objects.requireNonNull(registry, "registry");
    return create(
        name,
        workers,
        queueCapacity,
        registry,
        AwsCredentialSources.create(TransportSecurityPolicy.system()));
  }

  /** Creates an owner of the supplied source; pass a non-owning view for a borrowed provider. */
  public static AsyncAwsClients create(
      String name,
      int workers,
      int queueCapacity,
      MeterRegistry registry,
      AwsCredentialSource source) {
    try {
      requireName(name);
      Objects.requireNonNull(registry, "registry");
    } catch (RuntimeException | Error failure) {
      source.close();
      throw failure;
    }
    var owner =
        new AsyncAwsClients(
            source,
            Clock.systemUTC(),
            Duration.ofSeconds(15),
            workers,
            queueCapacity,
            AsyncAwsTransportSettings.defaults());
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
    owner.observeCompletions(pool -> bind(name, pool, registry));
  }

  private static void requireName(String name) {
    if (name.isBlank()) throw new IllegalArgumentException("poolName must not be blank");
  }

  static synchronized CompletionObserver bind(
      String name, CompletionPool executor, MeterRegistry registry) {
    Map<String, Group> groups = GROUPS.computeIfAbsent(registry, ignored -> new HashMap<>());
    Group group;
    try {
      group = groups.computeIfAbsent(name, ignored -> new Group(name, registry));
    } catch (RuntimeException | Error failure) {
      if (groups.isEmpty()) GROUPS.remove(registry);
      throw failure;
    }
    group.executors.add(executor);
    return new CompletionObserver() {
      private final AtomicBoolean closed = new AtomicBoolean();

      @Override
      public void rejected() {
        group.rejected();
      }

      @Override
      public void completed(long nanos) {
        group.completed(nanos);
      }

      @Override
      public void close() {
        if (closed.compareAndSet(false, true)) release(name, executor, registry, group);
      }
    };
  }

  private static synchronized void release(
      String name, CompletionPool executor, MeterRegistry registry, Group group) {
    group.executors.remove(executor);
    Map<String, Group> groups = GROUPS.get(registry);
    if (groups != null && group.executors.isEmpty() && groups.remove(name, group)) {
      group.meters.forEach(registry::remove);
      if (groups.isEmpty()) GROUPS.remove(registry);
    }
  }

  private static final class Group implements CompletionObserver {
    final Set<CompletionPool> executors = ConcurrentHashMap.newKeySet();
    final List<Meter> meters;
    final Counter rejected;
    final Timer latency;

    Group(String name, MeterRegistry registry) {
      var registered = new ArrayList<Meter>();
      try {
        var pending =
            Gauge.builder(
                    "axiz.transport.pending",
                    this,
                    g ->
                        g.executors.stream()
                            .mapToInt(e -> e.getActiveCount() + e.getQueueSize())
                            .sum())
                .tag("transport", name)
                .register(registry);
        registered.add(pending);
        var queued =
            Gauge.builder(
                    "axiz.transport.queued",
                    this,
                    g -> g.executors.stream().mapToInt(e -> e.getQueueSize()).sum())
                .tag("transport", name)
                .register(registry);
        registered.add(queued);
        var active =
            Gauge.builder(
                    "axiz.transport.active",
                    this,
                    g -> g.executors.stream().mapToInt(CompletionPool::getActiveCount).sum())
                .tag("transport", name)
                .register(registry);
        registered.add(active);
        rejected = registry.counter("axiz.transport.rejected", "transport", name);
        registered.add(rejected);
        latency = registry.timer("axiz.transport.latency", "transport", name);
        registered.add(latency);
        meters = List.copyOf(registered);
      } catch (RuntimeException | Error failure) {
        registered.forEach(registry::remove);
        throw failure;
      }
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

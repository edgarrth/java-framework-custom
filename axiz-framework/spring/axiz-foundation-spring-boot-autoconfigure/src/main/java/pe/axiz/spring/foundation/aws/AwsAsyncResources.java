package pe.axiz.spring.foundation.aws;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.observability.aws.AwsCompletionMetrics;

/** Spring composition of context identity, native transport and optional context metrics. */
public final class AwsAsyncResources {
  private AwsAsyncResources() {}

  public static AsyncAwsClients create(
      String name,
      int workers,
      int capacity,
      @Nullable MeterRegistry registry,
      AwsIdentity identity,
      AwsService service,
      AwsCapability capability,
      AsyncAwsTransportSettings transportSettings) {
    var source = identity.source(service, capability);
    AsyncAwsClients owner;
    try {
      owner =
          new AsyncAwsClients(
              source,
              Clock.systemUTC(),
              Duration.ofSeconds(15),
              workers,
              capacity,
              transportSettings);
    } catch (RuntimeException | Error failure) {
      source.close();
      throw failure;
    }
    try {
      if (registry != null) AwsCompletionMetrics.bind(owner, name, registry);
      return owner;
    } catch (RuntimeException | Error failure) {
      owner.close();
      throw failure;
    }
  }
}

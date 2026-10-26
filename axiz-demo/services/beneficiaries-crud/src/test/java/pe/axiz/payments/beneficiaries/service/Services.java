package pe.axiz.payments.beneficiaries.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import pe.axiz.core.cache.AxizCache;
import pe.axiz.core.cache.InMemoryByteCache;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.payments.beneficiaries.repository.BeneficiaryRepository;

/** The service as the application wires it, over a test repository and clock. */
public final class Services {

  private Services() {}

  /** Ids B-1, B-2... and at most {@code maxPerCustomer} beneficiaries per customer. */
  public static BeneficiariesService beneficiaries(
      BeneficiaryRepository repository,
      Clock clock,
      SimpleMeterRegistry meters,
      int maxPerCustomer) {
    AtomicInteger ids = new AtomicInteger();
    return new BeneficiariesService(
        repository,
        new AxizCache(
            "test",
            "beneficiaries",
            new InMemoryByteCache(100, clock),
            new ReferenceCodec(),
            Duration.ofMinutes(5)),
        IdentifierRegistry.withDiscoveredTypes(),
        () -> "B-" + ids.incrementAndGet(),
        new EventMetrics(meters, MetricsSettings.defaults()),
        clock,
        new BeneficiaryPolicy(maxPerCustomer));
  }
}

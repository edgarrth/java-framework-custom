package pe.axiz.payments.beneficiaries.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.payments.beneficiaries.repository.BeneficiaryRepository;

/** El servicio tal como lo cablea la aplicación, sobre un repositorio y un reloj de prueba. */
public final class Services {

  private Services() {}

  /** Ids B-1, B-2, etc., y como máximo {@code maxPerCustomer} beneficiarios por cliente. */
  public static BeneficiariesService beneficiaries(
      BeneficiaryRepository repository,
      Clock clock,
      SimpleMeterRegistry meters,
      int maxPerCustomer) {
    AtomicInteger ids = new AtomicInteger();
    return new BeneficiariesService(
        repository,
        IdentifierRegistry.withDiscoveredTypes(),
        () -> "B-" + ids.incrementAndGet(),
        new EventMetrics(meters, MetricsSettings.defaults()),
        clock,
        new BeneficiaryPolicy(maxPerCustomer));
  }
}

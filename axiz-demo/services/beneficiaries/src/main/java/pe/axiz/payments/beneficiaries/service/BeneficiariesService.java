package pe.axiz.payments.beneficiaries.service;

import java.time.Clock;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import pe.axiz.core.api.Page;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryErrors;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.payments.beneficiaries.model.NewBeneficiary;
import pe.axiz.payments.beneficiaries.repository.BeneficiaryRepository;

/**
 * Operaciones de negocio sobre beneficiarios: validación del CCI, un beneficiario activo por cuenta
 * y cliente, un máximo por cliente y eliminación lógica. La validación de pagos siempre lee el
 * directorio de referencia; las cachés locales no pueden garantizar la revocación entre réplicas.
 */
@Service
public class BeneficiariesService {

  static final String RESOURCE = "beneficiary";

  private final BeneficiaryRepository repository;
  private final IdentifierRegistry identifiers;
  private final IdGenerator ids;
  private final EventMetrics metrics;
  private final Clock clock;
  private final int maxPerCustomer;

  public BeneficiariesService(
      BeneficiaryRepository repository,
      IdentifierRegistry identifiers,
      IdGenerator ids,
      EventMetrics metrics,
      Clock clock,
      BeneficiaryPolicy policy) {
    this.repository = repository;
    this.identifiers = identifiers;
    this.ids = ids;
    this.metrics = metrics;
    this.clock = clock;
    this.maxPerCustomer = policy.maxPerCustomer();
  }

  /** Una página de beneficiarios activos (paginación por cursor). */
  public Page<Beneficiary> list(String customerId, int size, @Nullable String cursor) {
    return repository.listActive(customerId, size, cursor);
  }

  /** Registra un beneficiario tras validar la cuenta y las reglas por cliente. */
  public Beneficiary create(String customerId, NewBeneficiary data) {
    if (!identifiers.isValid("cci-strict", data.cci())) {
      reject("invalid-account");
      throw new BusinessException(BeneficiaryErrors.INVALID_ACCOUNT, "invalid cci", Map.of());
    }
    Beneficiary created = Beneficiary.register(customerId, ids.newId(), data, clock.instant());
    try {
      repository.insert(created, maxPerCustomer);
    } catch (BusinessException failure) {
      reject(
          failure.definition().equals(BeneficiaryErrors.DUPLICATE_ACCOUNT)
              ? "duplicate"
              : "limit-or-conflict");
      throw failure;
    }
    metrics.record(MetricEvent.of("beneficiary.registered").outcome(Outcome.SUCCESS));
    return created;
  }

  /**
   * Beneficiario activo según el directorio de referencia; los cambios en otra réplica son visibles
   * de inmediato.
   */
  public Beneficiary get(String customerId, String beneficiaryId) {
    return active(customerId, beneficiaryId);
  }

  /** Cambia el alias. */
  public Beneficiary rename(String customerId, String beneficiaryId, String alias) {
    Beneficiary current = active(customerId, beneficiaryId);
    current.rename(alias, clock.instant());
    Beneficiary saved = repository.update(current);
    return saved;
  }

  /** Eliminación lógica; el elemento se conserva para auditoría. */
  public void remove(String customerId, String beneficiaryId) {
    Beneficiary current = active(customerId, beneficiaryId);
    current.remove(clock.instant());
    repository.update(current);
    metrics.record(MetricEvent.of("beneficiary.removed").outcome(Outcome.SUCCESS));
  }

  private Beneficiary active(String customerId, String beneficiaryId) {
    return repository
        .findActive(customerId, beneficiaryId)
        .orElseThrow(() -> new NotFoundException(RESOURCE, beneficiaryId));
  }

  private void reject(String reason) {
    metrics.record(
        MetricEvent.of("beneficiary.registered").outcome(Outcome.REJECTED).reason(reason));
  }
}

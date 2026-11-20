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
 * Business operations on beneficiaries: CCI validation, one active beneficiary per account and
 * customer, a maximum per customer and logical removal. Payment validation always reads the
 * authoritative directory; local caches cannot enforce revocation across replicas.
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

  /** One page of active beneficiaries (cursor pagination). */
  public Page<Beneficiary> list(String customerId, int size, @Nullable String cursor) {
    return repository.listActive(customerId, size, cursor);
  }

  /** Registers a beneficiary after validating the account and the per-customer rules. */
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

  /** Authoritative active beneficiary; changes on another replica are visible immediately. */
  public Beneficiary get(String customerId, String beneficiaryId) {
    return active(customerId, beneficiaryId);
  }

  /** Changes the alias. */
  public Beneficiary rename(String customerId, String beneficiaryId, String alias) {
    Beneficiary current = active(customerId, beneficiaryId);
    current.rename(alias, clock.instant());
    Beneficiary saved = repository.update(current);
    return saved;
  }

  /** Logical removal; the item is kept for audit. */
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

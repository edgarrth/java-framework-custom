package pe.axiz.payments.beneficiaries.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import pe.axiz.core.api.Page;
import pe.axiz.core.cache.AxizCache;
import pe.axiz.core.cache.CacheKeys;
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
 * customer, a maximum per customer, logical removal and a read-through cache of single items
 * (evicted on every change).
 */
@Service
public class BeneficiariesService {

  static final String RESOURCE = "beneficiary";

  private final BeneficiaryRepository repository;
  private final AxizCache cache;
  private final IdentifierRegistry identifiers;
  private final IdGenerator ids;
  private final EventMetrics metrics;
  private final Clock clock;
  private final int maxPerCustomer;

  public BeneficiariesService(
      BeneficiaryRepository repository,
      AxizCache beneficiariesCache,
      IdentifierRegistry identifiers,
      IdGenerator ids,
      EventMetrics metrics,
      Clock clock,
      BeneficiaryPolicy policy) {
    this.repository = repository;
    this.cache = beneficiariesCache;
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
    List<Beneficiary> current = repository.allActive(customerId);
    if (current.stream().anyMatch(b -> b.sameAccount(data.cci()))) {
      reject("duplicate");
      throw new BusinessException(
          BeneficiaryErrors.DUPLICATE_ACCOUNT, "duplicate account", Map.of());
    }
    if (current.size() >= maxPerCustomer) {
      reject("limit");
      throw new BusinessException(
          BeneficiaryErrors.LIMIT_REACHED,
          "limit reached",
          Map.of("max", Integer.toString(maxPerCustomer)));
    }
    Beneficiary created = Beneficiary.register(customerId, ids.newId(), data, clock.instant());
    repository.insert(created);
    metrics.record(MetricEvent.of("beneficiary.registered").outcome(Outcome.SUCCESS));
    return created;
  }

  /** The active beneficiary (cached). */
  public Beneficiary get(String customerId, String beneficiaryId) {
    Beneficiary found =
        cache.getOrLoad(
            key(customerId, beneficiaryId),
            Beneficiary.class,
            () -> repository.findActive(customerId, beneficiaryId).orElse(null));
    if (found == null) {
      throw new NotFoundException(RESOURCE, beneficiaryId);
    }
    return found;
  }

  /** Changes the alias. */
  public Beneficiary rename(String customerId, String beneficiaryId, String alias) {
    Beneficiary current = active(customerId, beneficiaryId);
    current.rename(alias, clock.instant());
    Beneficiary saved = repository.update(current);
    cache.evict(key(customerId, beneficiaryId));
    return saved;
  }

  /** Logical removal; the item is kept for audit. */
  public void remove(String customerId, String beneficiaryId) {
    Beneficiary current = active(customerId, beneficiaryId);
    current.remove(clock.instant());
    repository.update(current);
    cache.evict(key(customerId, beneficiaryId));
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

  private static String key(String customerId, String beneficiaryId) {
    return CacheKeys.of(customerId, beneficiaryId);
  }
}

package pe.axiz.payments.beneficiaries.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageMetadata;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.payments.beneficiaries.repository.BeneficiaryRepository;

/** In-memory repository with the same semantics as DynamoDB (active only, insertion order). */
public final class InMemoryBeneficiaries implements BeneficiaryRepository {

  /** Every stored item, removed ones included, by {@code customerId/beneficiaryId}. */
  public final Map<String, Beneficiary> rows = new LinkedHashMap<>();

  /** Single-item reads served. */
  public int reads;

  @Override
  public Optional<Beneficiary> findActive(String customerId, String beneficiaryId) {
    reads++;
    return Optional.ofNullable(rows.get(customerId + "/" + beneficiaryId))
        .filter(b -> !b.removed());
  }

  @Override
  public Page<Beneficiary> listActive(String customerId, int size, @Nullable String cursor) {
    List<Beneficiary> all = allActive(customerId);
    int from = cursor == null ? 0 : Integer.parseInt(cursor);
    int to = Math.min(all.size(), from + size);
    return new Page<>(
        all.subList(from, to),
        new PageMetadata(0, size, -1, -1),
        to < all.size() ? Integer.toString(to) : null);
  }

  @Override
  public List<Beneficiary> allActive(String customerId) {
    return rows.values().stream()
        .filter(b -> b.getCustomerId().equals(customerId) && !b.removed())
        .toList();
  }

  @Override
  public synchronized void insert(Beneficiary b, int maximum) {
    new BeneficiaryPolicy(maximum).checkRegistration(allActive(b.getCustomerId()), b.getCci());
    if (rows.putIfAbsent(b.getCustomerId() + "/" + b.getBeneficiaryId(), b) != null) {
      throw new IllegalStateException("exists");
    }
  }

  @Override
  public Beneficiary update(Beneficiary b) {
    rows.put(b.getCustomerId() + "/" + b.getBeneficiaryId(), b);
    return b;
  }
}

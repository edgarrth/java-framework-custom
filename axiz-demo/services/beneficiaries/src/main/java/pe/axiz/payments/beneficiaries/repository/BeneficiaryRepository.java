package pe.axiz.payments.beneficiaries.repository;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.payments.beneficiaries.model.Beneficiary;

/** Storage of beneficiaries; only active (not removed) items are returned. */
public interface BeneficiaryRepository {

  /** The active beneficiary, if any. */
  Optional<Beneficiary> findActive(String customerId, String beneficiaryId);

  /**
   * One page of active beneficiaries of a customer.
   *
   * @param cursor opaque cursor of the previous page, or null for the first one
   */
  Page<Beneficiary> listActive(String customerId, int size, @Nullable String cursor);

  /** Every active beneficiary of a customer (bounded by the per-customer limit). */
  List<Beneficiary> allActive(String customerId);

  /** Inserts a new beneficiary; fails if the key already exists. */
  void insert(Beneficiary beneficiary);

  /** Writes a change with optimistic locking. */
  Beneficiary update(Beneficiary beneficiary);
}

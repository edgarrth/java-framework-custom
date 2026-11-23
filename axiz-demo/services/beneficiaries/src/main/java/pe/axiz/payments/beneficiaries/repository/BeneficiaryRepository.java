package pe.axiz.payments.beneficiaries.repository;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.payments.beneficiaries.model.Beneficiary;

/** Almacenamiento de beneficiarios; solo se devuelven los elementos activos (no eliminados). */
public interface BeneficiaryRepository {

  /** El beneficiario activo, si existe. */
  Optional<Beneficiary> findActive(String customerId, String beneficiaryId);

  /**
   * Una página de beneficiarios activos de un cliente.
   *
   * @param cursor cursor opaco de la página previa, o null para la primera
   */
  Page<Beneficiary> listActive(String customerId, int size, @Nullable String cursor);

  /** Todos los beneficiarios activos de un cliente (acotados por el límite por cliente). */
  List<Beneficiary> allActive(String customerId);

  /**
   * Garantiza de forma atómica la unicidad de la cuenta y el límite del cliente al insertar una
   * clave nueva.
   */
  void insert(Beneficiary beneficiary, int maxPerCustomer);

  /** Escribe un cambio con bloqueo optimista. */
  Beneficiary update(Beneficiary beneficiary);
}

package pe.axiz.payments.beneficiaries.controller;

import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.payments.beneficiaries.model.api.Beneficiary;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryPage;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryRequest;
import pe.axiz.payments.beneficiaries.model.api.BeneficiaryUpdate;
import pe.axiz.payments.beneficiaries.service.BeneficiariesService;
import pe.axiz.spring.web.Paging;

/**
 * Adaptador REST de las operaciones {@code beneficiaries} del contrato (src/main/openapi). La
 * validación de formatos proviene de los modelos del contrato; las reglas de negocio residen en el
 * servicio. Cada operación se limita al cliente de la ruta, previo a las repeticiones de
 * idempotencia.
 */
@RestController
public class BeneficiariesController {

  /** Solo el cliente indicado en la ruta (ver {@link CustomerAccess}). */
  static final String CUSTOMER_ONLY = "@customerAccess.isCustomer(authentication, #customerId)";

  private final BeneficiariesService service;

  public BeneficiariesController(BeneficiariesService service) {
    this.service = service;
  }

  /**
   * Beneficiarios de un cliente (paginación por cursor) (GET
   * /v1/customers/{customerId}/beneficiaries).
   */
  @PreAuthorize(CUSTOMER_ONLY)
  @GetMapping("/v1/customers/{customerId}/beneficiaries")
  public ResponseEntity<BeneficiaryPage> listBeneficiaries(
      @PathVariable("customerId") String customerId,
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @Paging PageRequest page) {
    return ResponseEntity.ok(
        BeneficiaryApiMapper.toApi(service.list(customerId, page.size(), cursor)));
  }

  /** Registra un beneficiario (POST /v1/customers/{customerId}/beneficiaries). */
  @Idempotent
  @PreAuthorize(CUSTOMER_ONLY)
  @PostMapping("/v1/customers/{customerId}/beneficiaries")
  public ResponseEntity<Beneficiary> createBeneficiary(
      @PathVariable("customerId") String customerId, @Valid @RequestBody BeneficiaryRequest body) {
    var created = service.create(customerId, BeneficiaryApiMapper.toNew(body));
    return ResponseEntity.status(201).body(BeneficiaryApiMapper.toApi(created));
  }

  /** Obtiene un beneficiario (GET /v1/customers/{customerId}/beneficiaries/{beneficiaryId}). */
  @PreAuthorize(CUSTOMER_ONLY)
  @GetMapping("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  public ResponseEntity<Beneficiary> getBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId) {
    return ResponseEntity.ok(BeneficiaryApiMapper.toApi(service.get(customerId, beneficiaryId)));
  }

  /**
   * Actualiza el alias de un beneficiario (PUT
   * /v1/customers/{customerId}/beneficiaries/{beneficiaryId}).
   */
  @PreAuthorize(CUSTOMER_ONLY)
  @PutMapping("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  public ResponseEntity<Beneficiary> updateBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId,
      @Valid @RequestBody BeneficiaryUpdate body) {
    var renamed = service.rename(customerId, beneficiaryId, body.getAlias());
    return ResponseEntity.ok(BeneficiaryApiMapper.toApi(renamed));
  }

  /**
   * Elimina un beneficiario (borrado lógico) (DELETE
   * /v1/customers/{customerId}/beneficiaries/{beneficiaryId}).
   */
  @PreAuthorize(CUSTOMER_ONLY)
  @DeleteMapping("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  public ResponseEntity<Void> deleteBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId) {
    service.remove(customerId, beneficiaryId);
    return ResponseEntity.noContent().build();
  }
}

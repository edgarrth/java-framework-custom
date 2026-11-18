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
import pe.axiz.payments.beneficiaries.api.model.Beneficiary;
import pe.axiz.payments.beneficiaries.api.model.BeneficiaryPage;
import pe.axiz.payments.beneficiaries.api.model.BeneficiaryRequest;
import pe.axiz.payments.beneficiaries.api.model.BeneficiaryUpdate;
import pe.axiz.payments.beneficiaries.service.BeneficiariesService;
import pe.axiz.spring.web.Paging;

/**
 * REST adapter of the {@code beneficiaries} operations of the contract (src/main/openapi).
 * Validation of formats comes from the contract models; business rules live in the service. Every
 * operation is limited to the customer of the path, before idempotency replays.
 */
@RestController
public class BeneficiariesController {

  /** Only the customer named in the path (see {@link CustomerAccess}). */
  static final String CUSTOMER_ONLY = "@customerAccess.isCustomer(authentication, #customerId)";

  private final BeneficiariesService service;

  public BeneficiariesController(BeneficiariesService service) {
    this.service = service;
  }

  /**
   * Beneficiaries of a customer (cursor pagination) (GET /v1/customers/{customerId}/beneficiaries).
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

  /** Registers a beneficiary (POST /v1/customers/{customerId}/beneficiaries). */
  @Idempotent
  @PreAuthorize(CUSTOMER_ONLY)
  @PostMapping("/v1/customers/{customerId}/beneficiaries")
  public ResponseEntity<Beneficiary> createBeneficiary(
      @PathVariable("customerId") String customerId, @Valid @RequestBody BeneficiaryRequest body) {
    var created = service.create(customerId, BeneficiaryApiMapper.toNew(body));
    return ResponseEntity.status(201).body(BeneficiaryApiMapper.toApi(created));
  }

  /** Gets a beneficiary (GET /v1/customers/{customerId}/beneficiaries/{beneficiaryId}). */
  @PreAuthorize(CUSTOMER_ONLY)
  @GetMapping("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  public ResponseEntity<Beneficiary> getBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId) {
    return ResponseEntity.ok(BeneficiaryApiMapper.toApi(service.get(customerId, beneficiaryId)));
  }

  /**
   * Updates the alias of a beneficiary (PUT
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
   * Removes a beneficiary (soft delete) (DELETE
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

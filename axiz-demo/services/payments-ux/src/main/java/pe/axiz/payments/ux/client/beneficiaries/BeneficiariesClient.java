package pe.axiz.payments.ux.client.beneficiaries;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import pe.axiz.payments.ux.client.beneficiaries.model.Beneficiary;
import pe.axiz.payments.ux.client.beneficiaries.model.BeneficiaryPage;
import pe.axiz.payments.ux.client.beneficiaries.model.BeneficiaryRequest;
import pe.axiz.payments.ux.client.beneficiaries.model.BeneficiaryUpdate;
import reactor.core.publisher.Mono;

/**
 * Cliente de la API Frequent beneficiaries (1.0.0), grupo de servicios HTTP {@code beneficiaries}:
 * la URL base, los timeouts, los reintentos, el circuit breaker y la autenticación provienen de
 * {@code axiz.http-client.clients.beneficiaries.*}.
 */
@HttpExchange
public interface BeneficiariesClient {

  /**
   * Beneficiarios de un cliente (paginación por cursor) (GET
   * /v1/customers/{customerId}/beneficiaries).
   */
  @GetExchange("/v1/customers/{customerId}/beneficiaries")
  Mono<BeneficiaryPage> listBeneficiaries(
      @PathVariable("customerId") String customerId,
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "size", required = false) @Nullable Integer size);

  /** Registra un beneficiario (POST /v1/customers/{customerId}/beneficiaries). */
  @PostExchange("/v1/customers/{customerId}/beneficiaries")
  Mono<Beneficiary> createBeneficiary(
      @PathVariable("customerId") String customerId,
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody BeneficiaryRequest body);

  /** Obtiene un beneficiario (GET /v1/customers/{customerId}/beneficiaries/{beneficiaryId}). */
  @GetExchange("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  Mono<Beneficiary> getBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId);

  /**
   * Actualiza el alias de un beneficiario (PUT
   * /v1/customers/{customerId}/beneficiaries/{beneficiaryId}).
   */
  @PutExchange("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  Mono<Beneficiary> updateBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId,
      @RequestBody BeneficiaryUpdate body);

  /**
   * Elimina un beneficiario (borrado lógico) (DELETE
   * /v1/customers/{customerId}/beneficiaries/{beneficiaryId}).
   */
  @DeleteExchange("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  Mono<Void> deleteBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId);
}

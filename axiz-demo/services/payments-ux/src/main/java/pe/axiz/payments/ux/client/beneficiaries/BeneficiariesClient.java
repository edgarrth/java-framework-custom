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
 * Client of the Frequent beneficiaries API (1.0.0), HTTP service group {@code beneficiaries}: base
 * URL, timeouts, retries, circuit breaker and authentication come from {@code
 * axiz.http-client.clients.beneficiaries.*}.
 */
@HttpExchange
public interface BeneficiariesClient {

  /**
   * Beneficiaries of a customer (cursor pagination) (GET /v1/customers/{customerId}/beneficiaries).
   */
  @GetExchange("/v1/customers/{customerId}/beneficiaries")
  Mono<BeneficiaryPage> listBeneficiaries(
      @PathVariable("customerId") String customerId,
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "size", required = false) @Nullable Integer size);

  /** Registers a beneficiary (POST /v1/customers/{customerId}/beneficiaries). */
  @PostExchange("/v1/customers/{customerId}/beneficiaries")
  Mono<Beneficiary> createBeneficiary(
      @PathVariable("customerId") String customerId,
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody BeneficiaryRequest body);

  /** Gets a beneficiary (GET /v1/customers/{customerId}/beneficiaries/{beneficiaryId}). */
  @GetExchange("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  Mono<Beneficiary> getBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId);

  /**
   * Updates the alias of a beneficiary (PUT
   * /v1/customers/{customerId}/beneficiaries/{beneficiaryId}).
   */
  @PutExchange("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  Mono<Beneficiary> updateBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId,
      @RequestBody BeneficiaryUpdate body);

  /**
   * Removes a beneficiary (soft delete) (DELETE
   * /v1/customers/{customerId}/beneficiaries/{beneficiaryId}).
   */
  @DeleteExchange("/v1/customers/{customerId}/beneficiaries/{beneficiaryId}")
  Mono<Void> deleteBeneficiary(
      @PathVariable("customerId") String customerId,
      @PathVariable("beneficiaryId") String beneficiaryId);
}

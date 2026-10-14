package pe.axiz.payments.experience.controller;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.payments.experience.api.model.BeneficiaryList;
import pe.axiz.payments.experience.composition.BeneficiariesComposition;
import pe.axiz.spring.web.Paging;
import reactor.core.publisher.Mono;

/**
 * REST adapter of the {@code beneficiaries} operations of the contract (src/main/openapi).
 * Generated once by axiz: keep the signatures in sync with the contract ({@code axiz contract sync}
 * adds new operations and reports removed ones); request and response models are generated at build
 * time.
 */
@RestController
public class BeneficiariesController {

  private final BeneficiariesComposition composition;

  public BeneficiariesController(BeneficiariesComposition composition) {
    this.composition = composition;
  }

  /** Frequent beneficiaries of the signed-in customer (GET /v1/beneficiaries). */
  @GetMapping("/v1/beneficiaries")
  public Mono<ResponseEntity<BeneficiaryList>> listMyBeneficiaries(
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @Paging PageRequest page) {
    return composition
        .listMyBeneficiaries(cursor, page)
        .map(result -> ResponseEntity.status(200).body(result));
  }
}

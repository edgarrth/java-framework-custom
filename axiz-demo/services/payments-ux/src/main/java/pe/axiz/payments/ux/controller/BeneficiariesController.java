package pe.axiz.payments.ux.controller;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.api.PageRequest;
import pe.axiz.payments.ux.composition.BeneficiariesComposition;
import pe.axiz.payments.ux.composition.model.BeneficiaryList;
import pe.axiz.spring.web.Paging;
import reactor.core.publisher.Mono;

/**
 * Adaptador REST de las operaciones {@code beneficiaries} del contrato (src/main/openapi). Generado
 * una sola vez por axiz: las firmas se mantienen sincronizadas con el contrato ({@code axiz
 * contract sync} agrega operaciones nuevas e informa las eliminadas); los modelos de solicitud y
 * respuesta se generan en tiempo de build.
 */
@RestController
@PreAuthorize("hasAuthority('SCOPE_payments:channel')")
public class BeneficiariesController {

  private final BeneficiariesComposition composition;

  public BeneficiariesController(BeneficiariesComposition composition) {
    this.composition = composition;
  }

  /** Beneficiarios frecuentes del cliente autenticado (GET /v1/beneficiaries). */
  @GetMapping("/v1/beneficiaries")
  public Mono<ResponseEntity<BeneficiaryList>> listMyBeneficiaries(
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @Paging PageRequest page) {
    return composition
        .listMyBeneficiaries(cursor, page)
        .map(result -> ResponseEntity.status(200).body(result));
  }
}

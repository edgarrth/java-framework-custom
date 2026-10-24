package pe.axiz.payments.coreintegration.adapter.in.rest;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.payments.coreintegration.api.model.Transfer;
import pe.axiz.payments.coreintegration.api.model.TransferRequest;
import pe.axiz.payments.coreintegration.application.usecase.SendTransferUseCase;

/**
 * REST adapter of the {@code transfers} operations of the contract (src/main/openapi). Only the
 * payment process sends transfers ({@code payments:process}).
 */
@RestController
@PreAuthorize("hasAuthority('SCOPE_payments:process')")
public class TransfersController {

  private final SendTransferUseCase sendTransferUseCase;

  public TransfersController(SendTransferUseCase sendTransferUseCase) {
    this.sendTransferUseCase = sendTransferUseCase;
  }

  /** Sends an inter-bank transfer to the payment network (POST /v1/transfers). */
  @PostMapping("/v1/transfers")
  public ResponseEntity<Transfer> sendTransfer(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody TransferRequest body) {
    var transfer =
        sendTransferUseCase.execute(
            new SendTransferUseCase.Input(RestMapper.order(body), idempotencyKey));
    return ResponseEntity.status(201).body(RestMapper.toApi(transfer));
  }
}

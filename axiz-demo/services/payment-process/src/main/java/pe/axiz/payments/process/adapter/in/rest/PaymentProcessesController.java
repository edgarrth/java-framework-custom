package pe.axiz.payments.process.adapter.in.rest;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.money.Money;
import pe.axiz.payments.process.adapter.in.rest.model.ProcessRequest;
import pe.axiz.payments.process.adapter.in.rest.model.ProcessView;
import pe.axiz.payments.process.application.usecase.GetPaymentProcessUseCase;
import pe.axiz.payments.process.application.usecase.StartPaymentProcessUseCase;
import pe.axiz.payments.process.domain.PaymentInstruction;
import pe.axiz.payments.process.domain.PaymentProcess;

/**
 * Adaptador REST de las operaciones {@code payment-processes} (src/main/openapi). Los procesos
 * normalmente se inician a partir del evento {@code payment-order.created}; este endpoint sirve
 * para operaciones y pruebas.
 */
@RestController
public class PaymentProcessesController {

  private final StartPaymentProcessUseCase startPaymentProcessUseCase;
  private final GetPaymentProcessUseCase getPaymentProcessUseCase;

  public PaymentProcessesController(
      StartPaymentProcessUseCase startPaymentProcessUseCase,
      GetPaymentProcessUseCase getPaymentProcessUseCase) {
    this.startPaymentProcessUseCase = startPaymentProcessUseCase;
    this.getPaymentProcessUseCase = getPaymentProcessUseCase;
  }

  /**
   * Inicia la saga de pago de una orden (POST /v1/payment-processes). Es una acción de operaciones:
   * solo invocadores con {@code payments:process}; los canales nunca inician procesos de forma
   * directa.
   */
  @PostMapping("/v1/payment-processes")
  @PreAuthorize("hasAuthority('SCOPE_payments:process')")
  public ResponseEntity<ProcessView> startPaymentProcess(@Valid @RequestBody ProcessRequest body) {
    PaymentInstruction instruction =
        new PaymentInstruction(
            body.getPaymentOrderId(),
            body.getOperationNumber(),
            body.getDebtorAccount(),
            body.getCreditorCci(),
            Money.of(body.getAmount(), body.getCurrency()),
            body.getCustomerId());
    PaymentProcess process =
        startPaymentProcessUseCase.execute(new StartPaymentProcessUseCase.Input(instruction));
    return ResponseEntity.status(201).body(toApi(process));
  }

  /** Estado de una saga de pago (GET /v1/payment-processes/{processId}). */
  @GetMapping("/v1/payment-processes/{processId}")
  @PreAuthorize("hasAnyAuthority('SCOPE_payments:process', 'SCOPE_payments:read')")
  public ResponseEntity<ProcessView> getPaymentProcess(
      @PathVariable("processId") String processId) {
    return ResponseEntity.ok(
        toApi(getPaymentProcessUseCase.execute(new GetPaymentProcessUseCase.Input(processId))));
  }

  static ProcessView toApi(PaymentProcess p) {
    return new ProcessView()
        .processId(p.processId())
        .status(p.status().name())
        .failedStep(p.failedStep());
  }
}

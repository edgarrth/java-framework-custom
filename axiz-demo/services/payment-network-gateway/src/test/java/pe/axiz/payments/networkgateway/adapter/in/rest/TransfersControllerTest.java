package pe.axiz.payments.networkgateway.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.payments.networkgateway.adapter.in.rest.model.Transfer;
import pe.axiz.payments.networkgateway.adapter.in.rest.model.TransferRequest;
import pe.axiz.payments.networkgateway.application.usecase.SendTransferUseCase;
import pe.axiz.payments.networkgateway.domain.TransferStatus;

class TransfersControllerTest {
  final EventMetrics metrics =
      new EventMetrics(new SimpleMeterRegistry(), MetricsSettings.defaults());

  @Test
  void aTransferIsSentWithTheCallersKey() {
    List<String> sent = new ArrayList<>();
    TransfersController transfers =
        new TransfersController(
            new SendTransferUseCase(
                (order, key) -> {
                  sent.add(order.creditorCci() + " " + order.amount().amount() + " " + key);
                  return new pe.axiz.payments.networkgateway.domain.Transfer(
                      "NET-1", TransferStatus.SETTLED, order.reference());
                },
                IdentifierRegistry.withDiscoveredTypes(),
                metrics));

    var response =
        transfers.sendTransfer(
            "key-4",
            new TransferRequest()
                .creditorCci("00219100123456789053")
                .amount(new BigDecimal("10.00"))
                .currency("PEN")
                .reference("E2E-1"));

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    Transfer body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getTransferId()).isEqualTo("NET-1");
    assertThat(body.getStatus()).isEqualTo("SETTLED");
    assertThat(body.getReference()).isEqualTo("E2E-1");
    assertThat(sent).containsExactly("00219100123456789053 10.00 key-4");
  }

  @Test
  void inexactAmountsNeverReachTheTransferNetwork() {
    var controller =
        new TransfersController(
            new SendTransferUseCase(
                (order, key) -> {
                  throw new AssertionError("Inexact amount reached network");
                },
                IdentifierRegistry.withDiscoveredTypes(),
                metrics));
    for (String amount : List.of("150.005", "150.755", "1e99999999")) {
      var request =
          new TransferRequest()
              .creditorCci("00219100123456789053")
              .amount(new BigDecimal(amount))
              .currency("PEN")
              .reference("EXACT-1");
      assertThatThrownBy(() -> controller.sendTransfer("request-1", request))
          .isInstanceOf(pe.axiz.core.errors.BusinessException.class);
    }
  }
}

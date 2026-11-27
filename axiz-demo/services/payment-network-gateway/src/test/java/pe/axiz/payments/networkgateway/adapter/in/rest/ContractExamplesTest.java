package pe.axiz.payments.networkgateway.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import pe.axiz.payments.networkgateway.adapter.in.rest.model.TransferRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pruebas de contrato a partir de los ejemplos de solicitud del contrato OpenAPI: cada ejemplo debe
 * deserializarse en el modelo generado y superar su validación. Un cambio de contrato que rompe sus
 * propios ejemplos falla aquí, sin llegar a los consumidores.
 */
class ContractExamplesTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  @AfterAll
  static void close() {
    FACTORY.close();
  }

  private static <T> T example(String operationId, Class<T> type) throws IOException {
    String resource = "/contract-examples/" + operationId + ".json";
    try (InputStream in = ContractExamplesTest.class.getResourceAsStream(resource)) {
      return MAPPER.readValue(Objects.requireNonNull(in, resource), type);
    }
  }

  @Test
  void sendTransferExampleIsValid() throws IOException {
    TransferRequest body = example("sendTransfer", TransferRequest.class);
    assertThat(VALIDATOR.validate(body)).isEmpty();
  }
}

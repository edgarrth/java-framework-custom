package pe.axiz.payments.business.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import pe.axiz.payments.business.api.model.PaymentOrderRequest;
import pe.axiz.payments.business.api.model.StatusChange;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract tests from the request examples of the OpenAPI contract: each example must deserialize
 * into the generated model and pass its validation. A contract change that breaks its own examples
 * fails here, before reaching consumers.
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
  void createPaymentOrderExampleIsValid() throws IOException {
    PaymentOrderRequest body = example("createPaymentOrder", PaymentOrderRequest.class);
    assertThat(VALIDATOR.validate(body)).isEmpty();
  }

  @Test
  void changePaymentOrderStatusExampleIsValid() throws IOException {
    StatusChange body = example("changePaymentOrderStatus", StatusChange.class);
    assertThat(VALIDATOR.validate(body)).isEmpty();
  }
}

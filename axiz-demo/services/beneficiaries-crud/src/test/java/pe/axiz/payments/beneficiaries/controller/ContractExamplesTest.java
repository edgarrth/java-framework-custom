package pe.axiz.payments.beneficiaries.controller;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import pe.axiz.payments.beneficiaries.api.model.BeneficiaryRequest;
import pe.axiz.payments.beneficiaries.api.model.BeneficiaryUpdate;
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
  void createBeneficiaryExampleIsValid() throws IOException {
    BeneficiaryRequest body = example("createBeneficiary", BeneficiaryRequest.class);
    assertThat(VALIDATOR.validate(body)).isEmpty();
  }

  @Test
  void updateBeneficiaryExampleIsValid() throws IOException {
    BeneficiaryUpdate body = example("updateBeneficiary", BeneficiaryUpdate.class);
    assertThat(VALIDATOR.validate(body)).isEmpty();
  }
}

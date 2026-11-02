package pe.axiz.adapter.secrets.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

class AwsSecretsTest {

  @Test
  void secretsManagerPlainAndJsonField() {
    Map<String, String> store =
        Map.of(
            "axiz/payments/db",
            "{\"username\":\"app\",\"password\":\"p\\\"w\",\"port\":5432}",
            "axiz/payments/token",
            "t");
    SecretsManagerClient client =
        new SecretsManagerClient() {
          @Override
          public GetSecretValueResponse getSecretValue(GetSecretValueRequest r) {
            String v = store.get(r.secretId());
            if (v == null) {
              throw ResourceNotFoundException.builder().message("nope").build();
            }
            return GetSecretValueResponse.builder().secretString(v).build();
          }

          @Override
          public String serviceName() {
            return "secretsmanager";
          }

          @Override
          public void close() {}
        };
    SecretsManagerResolver resolver = new SecretsManagerResolver(client, "axiz/");
    assertEquals("t", resolver.require("payments/token").reveal());
    assertEquals("p\"w", resolver.require("payments/db#password").reveal());
    assertEquals("5432", resolver.require("payments/db#port").reveal());
    assertTrue(resolver.resolve("payments/db#missing").isEmpty());
    assertTrue(resolver.resolve("other").isEmpty());
    assertEquals(Optional.empty(), SecretsManagerResolver.JsonField.extract("{}", "a"));
  }

  @Test
  void parameterStoreWithDecryption() {
    SsmClient ssm =
        new SsmClient() {
          @Override
          public GetParameterResponse getParameter(GetParameterRequest r) {
            assertTrue(r.withDecryption());
            if (!r.name().equals("/axiz/dev/payments/partner-url")) {
              throw ParameterNotFoundException.builder().message("nope").build();
            }
            return GetParameterResponse.builder()
                .parameter(Parameter.builder().value("https://partner.example").build())
                .build();
          }

          @Override
          public String serviceName() {
            return "ssm";
          }

          @Override
          public void close() {}
        };
    ParameterStoreResolver resolver = new ParameterStoreResolver(ssm, "/axiz/dev/payments");
    assertEquals("https://partner.example", resolver.require("partner-url").reveal());
    assertTrue(resolver.resolve("x").isEmpty());
  }
}

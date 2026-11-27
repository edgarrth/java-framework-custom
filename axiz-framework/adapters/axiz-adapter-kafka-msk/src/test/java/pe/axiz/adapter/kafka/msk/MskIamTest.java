package pe.axiz.adapter.kafka.msk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MskIamTest {
  @Test
  void selectsTlsAndTheCredentialOwner() {
    var properties = MskIam.clientProperties();
    assertEquals("AWS_MSK_IAM", properties.get("sasl.mechanism"));
    assertEquals("SASL_SSL", properties.get("security.protocol"));
    assertEquals(
        AxizIamClientCallbackHandler.class.getName(),
        properties.get("sasl.client.callback.handler.class"));
  }
}

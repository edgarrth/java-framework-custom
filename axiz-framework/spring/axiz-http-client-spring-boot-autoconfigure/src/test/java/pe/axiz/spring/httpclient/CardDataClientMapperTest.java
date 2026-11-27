package pe.axiz.spring.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pe.axiz.core.carddata.Pan;
import pe.axiz.spring.sensitive.CardDataJacksonModule;
import tools.jackson.databind.json.JsonMapper;

class CardDataClientMapperTest {
  @Test
  void selectionIsPerClientAndNeverMutatesTheBaseMapper() {
    JsonMapper base = JsonMapper.builder().addModule(CardDataJacksonModule.masking()).build();
    JsonMapper processor = CardDataClientMapper.forClient(base, true);
    JsonMapper ordinary = CardDataClientMapper.forClient(base, false);
    Pan pan = Pan.parse("4111111111111111");
    assertEquals("\"4111111111111111\"", processor.writeValueAsString(pan));
    assertEquals("\"411111******1111\"", ordinary.writeValueAsString(pan));
    assertEquals("\"411111******1111\"", base.writeValueAsString(pan));
  }

  record Quote(String id) {}

  @Test
  void clientsReadProviderResponsesTolerantlyEvenWhenTheServiceIsStrict() {
    JsonMapper strict =
        JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    JsonMapper client = CardDataClientMapper.forClient(strict, false);
    assertEquals("q-1", client.readValue("{\"id\":\"q-1\",\"added\":true}", Quote.class).id());
    org.junit.jupiter.api.Assertions.assertThrows(
        tools.jackson.databind.exc.UnrecognizedPropertyException.class,
        () -> strict.readValue("{\"id\":\"q-1\",\"added\":true}", Quote.class));
  }
}

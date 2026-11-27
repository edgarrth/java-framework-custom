package pe.axiz.spring.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.carddata.CardExpiry;
import pe.axiz.core.carddata.CardVerificationCode;
import pe.axiz.core.carddata.Pan;
import pe.axiz.core.carddata.Pin;
import pe.axiz.core.secrets.SecretValue;
import tools.jackson.databind.json.JsonMapper;

class CardDataJacksonTest {
  @Test
  void applicationMapperMasksEvenWhenOptionalResponseMaskingIsDisabled() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class, AxizSensitiveValuesAutoConfiguration.class))
        .withPropertyValues("axiz.sensitive.enabled=false")
        .run(
            context -> {
              JsonMapper mapper = context.getBean(JsonMapper.class);
              try (var code = CardVerificationCode.parse("987");
                  var pin = Pin.parse("0123");
                  var secret = SecretValue.of("secret-value")) {
                String json =
                    mapper.writeValueAsString(
                        Map.of(
                            "card",
                            Pan.parse("4111111111111111"),
                            "nested",
                            List.of(code, pin, secret)));
                assertFalse(json.contains("4111111111111111"));
                assertFalse(json.contains("987"));
                assertFalse(json.contains("0123"));
                assertFalse(json.contains("secret-value"));
                assertTrue(json.contains("411111******1111"));
                assertEquals(
                    Pan.parse("4111111111111111"),
                    mapper.readValue("\"4111111111111111\"", Pan.class));
              }
            });
  }

  @Test
  void revealingMapperIsIsolatedAndDeserializationErrorsExcludeSubmittedValue() {
    JsonMapper base =
        JsonMapper.builder()
            .addModule(new SensitiveValueJacksonModule())
            .addModule(CardDataJacksonModule.masking())
            .build();
    JsonMapper outbound = base.rebuild().addModule(CardDataJacksonModule.revealing()).build();
    Pan pan = Pan.parse("4111111111111111");
    assertEquals("\"4111111111111111\"", outbound.writeValueAsString(pan));
    assertEquals("\"411111******1111\"", base.writeValueAsString(pan));
    try (var code = base.readValue("\"987\"", CardVerificationCode.class);
        var pin = base.readValue("\"0123\"", Pin.class)) {
      assertEquals("\"987\"", outbound.writeValueAsString(code));
      assertEquals("\"0123\"", outbound.writeValueAsString(pin));
      assertEquals("\"***\"", base.writeValueAsString(code));
    }
    assertEquals(
        "\"11/26\"", base.writeValueAsString(base.readValue("\"11/26\"", CardExpiry.class)));
    RuntimeException invalid =
        assertThrows(
            RuntimeException.class, () -> base.readValue("\"4111111111111112\"", Pan.class));
    assertFalse(invalid.getMessage().contains("4111111111111112"));
    assertThrows(RuntimeException.class, () -> base.readValue("4111111111111111", Pan.class));
  }
}

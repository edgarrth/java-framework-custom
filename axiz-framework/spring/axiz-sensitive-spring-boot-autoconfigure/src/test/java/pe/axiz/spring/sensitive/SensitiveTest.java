package pe.axiz.spring.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.sensitive.Mask;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.MaskingPolicy;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import tools.jackson.databind.json.JsonMapper;

class SensitiveTest {

  record Customer(
      String name,
      @Sensitive(level = SensitivityLevel.CRITICAL) String password,
      @Mask(MaskType.EMAIL) String email,
      @Sensitive(level = SensitivityLevel.LOW) int score) {}

  @Test
  void responsesAreMaskedButKeepTypesWhenShown() {
    JsonMapper mapper =
        JsonMapper.builder()
            .addModule(new SensitiveJacksonModule(MaskingPolicy.defaults()))
            .build();
    String json = mapper.writeValueAsString(new Customer("Ana", "s3cr3t", "ana@example.com", 7));
    assertFalse(json.contains("s3cr3t"));
    assertFalse(json.contains("ana@example.com"));
    assertTrue(json.contains("\"score\":7"), json);
    assertTrue(json.contains("\"name\":\"Ana\""));
  }

  @Test
  void beansHonourConfiguredKeys() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSensitiveAutoConfiguration.class))
        .withPropertyValues(
            "axiz.sensitive.keys.customer-code.level=HIGH",
            "axiz.sensitive.keys.customer-code.mask=FULL")
        .run(
            ctx -> {
              ObjectMasker masker = ctx.getBean(ObjectMasker.class);
              Object described =
                  masker.describe(Map.of("customerCode", "ABC123"), OutputChannel.LOG);
              assertEquals(false, described.toString().contains("ABC123"));
              assertTrue(ctx.containsBean("axizSensitiveJacksonModule"));
            });
  }
}

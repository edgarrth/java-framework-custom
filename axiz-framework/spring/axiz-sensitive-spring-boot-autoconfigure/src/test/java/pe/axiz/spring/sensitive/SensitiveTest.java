package pe.axiz.spring.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
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
              // the application's JsonMapper is never masked: no global Jackson module
              assertTrue(ctx.getBeansOfType(SensitiveJacksonModule.class).isEmpty());
            });
  }

  record PaymentCreated(
      String orderId, @Sensitive(level = SensitivityLevel.MEDIUM) String debtorAccount) {}

  @Test
  void onlyServerResponsesAreMaskedTheApplicationMapperKeepsTheValues() {
    // events, saga contexts, caches and outgoing calls use the application's mapper: a masked
    // account there would reach the consumer (and the core) as "*********5678"
    JsonMapper app = JsonMapper.builder().build();
    StaticListableBeanFactory beans =
        new StaticListableBeanFactory(Map.<String, Object>of("jsonMapper", app));
    JsonMapper responses =
        AxizSensitiveAutoConfiguration.responseMapper(
            beans.getBeanProvider(JsonMapper.class), MaskingPolicy.defaults());
    PaymentCreated event = new PaymentCreated("o-1", "1910012345678");
    assertFalse(responses.writeValueAsString(event).contains("1910012345678"));
    assertTrue(
        app.writeValueAsString(event).contains("1910012345678"), "the base mapper is unchanged");
  }

  @Test
  void servletAndReactiveResponsesGetTheMaskingConverter() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSensitiveAutoConfiguration.class))
        .run(ctx -> assertTrue(ctx.containsBean("axizSensitiveResponseConverter")));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSensitiveAutoConfiguration.class))
        .run(ctx -> assertTrue(ctx.containsBean("axizSensitiveResponseEncoder")));
  }
}

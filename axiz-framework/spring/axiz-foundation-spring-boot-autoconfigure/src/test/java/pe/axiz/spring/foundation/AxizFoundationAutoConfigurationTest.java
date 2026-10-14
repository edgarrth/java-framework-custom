package pe.axiz.spring.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.money.Money;
import pe.axiz.core.time.BusinessCalendar;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.core.time.TimeSettings;
import tools.jackson.databind.json.JsonMapper;

class AxizFoundationAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizFoundationAutoConfiguration.class));

  @Test
  void defaultsAndCalendarFromProperties() {
    runner
        .withPropertyValues(
            "axiz.foundation.calendar.recurring-holidays=07-28,07-29",
            "axiz.foundation.calendar.holidays=2026-12-08",
            "axiz.foundation.cutoff=15:00")
        .run(
            ctx -> {
              assertEquals(ZoneId.of("America/Lima"), ctx.getBean(TimeSettings.class).zone());
              BusinessCalendar cal = ctx.getBean(BusinessCalendar.class);
              assertFalse(cal.isBusinessDay(LocalDate.of(2026, 7, 28)));
              assertFalse(cal.isBusinessDay(LocalDate.of(2026, 12, 8)));
              assertTrue(cal.isBusinessDay(LocalDate.of(2026, 12, 9)));
              assertTrue(ctx.containsBean("axizCutoffTime"));
              assertTrue(ctx.getBean(CutoffTime.class).cutoff().getHour() == 15);
              assertTrue(ctx.getBean(IdGenerator.class).newId().length() == 36);
              assertTrue(ctx.getBean(IdentifierRegistry.class).isValid("ruc", "20100070970"));
            });
  }

  @Test
  void moneyJsonUsesStringAmounts() {
    JsonMapper mapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
    String json = mapper.writeValueAsString(Money.of("150.5", "PEN"));
    assertEquals("{\"amount\":\"150.50\",\"currency\":\"PEN\"}", json);
    assertEquals(Money.of("150.50", "PEN"), mapper.readValue(json, Money.class));
    assertEquals(Money.of("10", "USD"), mapper.readValue("\"USD 10.00\"", Money.class));
    assertEquals(
        Money.of("1.5", "PEN"),
        mapper.readValue("{\"amount\":1.5,\"currency\":\"PEN\"}", Money.class));
  }

  @Test
  void validatorUsesRegistry() {
    IdentifierConstraintValidator v = new IdentifierConstraintValidator();
    v.initialize(Holder.class.getRecordComponents()[0].getAnnotation(ValidIdentifier.class));
    assertTrue(v.isValid(null, null));
    assertTrue(v.isValid("12345678", null));
    assertFalse(v.isValid("123", null));
  }

  record Holder(@ValidIdentifier(type = "dni") String dni) {}
}

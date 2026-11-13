package pe.axiz.spring.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.money.Money;
import pe.axiz.core.time.BusinessCalendar;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.core.time.TimeSettings;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

class AxizFoundationAutoConfigurationTest {

  @Test
  void oversizedMoneyIsRejectedBeforeDecimalConversionEvenWithRelaxedParserLimits() {
    var factory =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(1_000_100).build())
            .build();
    JsonMapper mapper = JsonMapper.builder(factory).addModule(new MoneyJacksonModule()).build();
    String boundary = "0".repeat(Money.MAX_TEXT_LENGTH - 4) + "1.00";
    assertEquals(
        Money.of("1", "PEN"),
        mapper.readValue("{\"amount\":\"" + boundary + "\",\"currency\":\"PEN\"}", Money.class));
    for (String literal : List.of("9".repeat(257), "9".repeat(1_000_000))) {
      for (String json :
          List.of(
              "{\"amount\":\"" + literal + "\",\"currency\":\"PEN\"}",
              "{\"currency\":\"PEN\",\"amount\":" + literal + "}",
              "\"PEN " + literal + "\"")) {
        assertTimeout(
            Duration.ofSeconds(2),
            () -> assertThrows(RuntimeException.class, () -> mapper.readValue(json, Money.class)));
      }
    }
    assertThrows(
        JacksonException.class,
        () ->
            mapper.readValue(
                "{\"amount\":\"0" + boundary + "\",\"currency\":\"PEN\"}", Money.class));
  }

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
  void numericAndTextualMoneyKeepExactlyTheSameDecimalValue() {
    JsonMapper mapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
    for (String literal :
        List.of(
            "9007199254740992.01",
            "-9007199254740992.01",
            "999999999999999999999999999999.99",
            "1e-2",
            "150.500",
            "0")) {
      Money expected = Money.of(literal, "PEN");
      for (String amount : List.of(literal, "\"" + literal + "\"")) {
        for (String json :
            List.of(
                "{\"amount\":" + amount + ",\"currency\":\"PEN\"}",
                "{\"currency\":\"PEN\",\"extra\":{\"nested\":[1]},\"amount\":" + amount + "}")) {
          Money actual = mapper.readValue(json, Money.class);
          assertEquals(expected, actual);
          assertEquals(expected, mapper.readValue(mapper.writeValueAsString(actual), Money.class));
        }
      }
    }
    for (String value :
        List.of(
            "0.100000000000000000001",
            "-0.100000000000000000001",
            "9007199254740992.001",
            "1e-3")) {
      for (String amount : List.of(value, "\"" + value + "\"")) {
        assertThrows(
            RuntimeException.class,
            () ->
                mapper.readValue("{\"currency\":\"PEN\",\"amount\":" + amount + "}", Money.class));
      }
    }
  }

  @Test
  void invalidOrAmbiguousMoneyJsonIsRejected() {
    JsonMapper mapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
    for (String json :
        List.of(
            "[]",
            "42",
            "true",
            "{}",
            "{\"currency\":\"PEN\"}",
            "{\"amount\":null,\"currency\":\"PEN\"}",
            "{\"amount\":false,\"currency\":\"PEN\"}",
            "{\"amount\":[],\"currency\":\"PEN\"}",
            "{\"amount\":\"invalid\",\"currency\":\"PEN\"}",
            "{\"amount\":1,\"currency\":1}",
            "{\"amount\":1,\"currency\":null}",
            "{\"amount\":1,\"amount\":2,\"currency\":\"PEN\"}",
            "{\"amount\":1,\"currency\":\"PEN\",\"currency\":\"USD\"}",
            "{\"amount\":1,\"currency\":\"PEN\"")) {
      assertThrows(JacksonException.class, () -> mapper.readValue(json, Money.class), json);
    }
  }

  @Test
  void validatorUsesRegistry() {
    IdentifierConstraintValidator v = new IdentifierConstraintValidator();
    v.initialize(Holder.class.getRecordComponents()[0].getAnnotation(ValidIdentifier.class));
    assertTrue(v.isValid(null, null));
    assertTrue(v.isValid("12345678", null));
    assertFalse(v.isValid("123", null));
  }

  @Test
  void customIdentifierTypesReachTheValidatorAndAWorkingWeekendIsAllowed() {
    IdentifierType account =
        new IdentifierType() {
          @Override
          public String name() {
            return "internal-account";
          }

          @Override
          public boolean isValid(String normalizedValue) {
            return normalizedValue.matches("AC\\d{6}");
          }
        };
    runner
        .withBean(IdentifierType.class, () -> account)
        .withPropertyValues("axiz.foundation.calendar.weekend=", "axiz.foundation.zone=UTC")
        .run(
            ctx -> {
              assertTrue(
                  ctx.getBean(IdentifierRegistry.class).isValid("internal-account", "ac-123456"));
              IdentifierConstraintValidator v = new IdentifierConstraintValidator();
              v.initialize(
                  Account.class.getRecordComponents()[0].getAnnotation(ValidIdentifier.class));
              assertTrue(v.isValid("AC123456", null));
              assertFalse(v.isValid("AC12", null));
              // 2026-10-17 is a Saturday: with no weekend configured it is a business day
              assertTrue(
                  ctx.getBean(BusinessCalendar.class).isBusinessDay(LocalDate.of(2026, 10, 17)));
              assertEquals(ZoneId.of("UTC"), ctx.getBean(TimeSettings.class).zone());
              assertFalse(ctx.containsBean("axizCutoffTime"));
              assertTrue(ctx.containsBean("axizDueDates"));
              assertTrue(ctx.containsBean("axizMoneyModule"));
            });
  }

  @Test
  void moneyWithoutCurrencyIsRejected() {
    JsonMapper mapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
    assertThrows(
        JacksonException.class, () -> mapper.readValue("{\"amount\":\"1.00\"}", Money.class));
  }

  @Test
  void moneyFromClientsIsNeverRounded() {
    JsonMapper mapper = JsonMapper.builder().addModule(new MoneyJacksonModule()).build();
    for (String json :
        List.of(
            "{\"amount\":150.005,\"currency\":\"PEN\"}",
            "{\"amount\":\"150.005\",\"currency\":\"PEN\"}",
            "\"PEN 150.005\"",
            "{\"amount\":\"1e99999999\",\"currency\":\"PEN\"}")) {
      Throwable failure =
          assertThrows(RuntimeException.class, () -> mapper.readValue(json, Money.class));
      Throwable cause = failure;
      while (cause != null && !(cause instanceof BusinessException)) {
        cause = cause.getCause();
      }
      assertTrue(cause != null, json);
    }
    // a huge numeric exponent is refused as well (by the parser itself), and at once
    long start = System.nanoTime();
    assertThrows(
        RuntimeException.class,
        () -> mapper.readValue("{\"amount\":1e99999999,\"currency\":\"PEN\"}", Money.class));
    assertTrue(System.nanoTime() - start < 5_000_000_000L);
  }

  record Holder(@ValidIdentifier(type = "dni") String dni) {}

  record Account(@ValidIdentifier(type = "internal-account") String number) {}
}

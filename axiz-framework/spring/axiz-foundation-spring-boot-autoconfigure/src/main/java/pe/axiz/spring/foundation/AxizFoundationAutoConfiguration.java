package pe.axiz.spring.foundation;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.MonthDay;
import java.util.EnumSet;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.time.BusinessCalendar;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.core.time.DueDates;
import pe.axiz.core.time.TimeSettings;

/** Clock, business calendar, id generation, identifier registry and Money JSON support. */
@AutoConfiguration(
    beforeName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@EnableConfigurationProperties(AxizFoundationProperties.class)
public class AxizFoundationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Clock axizClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  TimeSettings axizTimeSettings(AxizFoundationProperties properties) {
    return new TimeSettings(properties.zone());
  }

  @Bean
  @ConditionalOnMissingBean
  BusinessCalendar axizBusinessCalendar(AxizFoundationProperties properties) {
    AxizFoundationProperties.Calendar c = properties.calendar();
    BusinessCalendar.Builder b = BusinessCalendar.builder(properties.zone());
    b.weekend(
        c.weekend().isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(c.weekend()));
    for (String md : c.recurringHolidays()) {
      b.recurringHoliday(MonthDay.parse("--" + md));
    }
    b.holidays(c.holidays());
    return b.build();
  }

  @Bean
  @ConditionalOnMissingBean
  DueDates axizDueDates(BusinessCalendar calendar) {
    return new DueDates(calendar);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty("axiz.foundation.cutoff")
  CutoffTime axizCutoffTime(AxizFoundationProperties properties, BusinessCalendar calendar) {
    return new CutoffTime(Objects.requireNonNull(properties.cutoff()), calendar);
  }

  @Bean
  @ConditionalOnMissingBean
  IdGenerator axizIdGenerator() {
    return IdGenerator.uuidV7();
  }

  @Bean
  @ConditionalOnMissingBean
  IdentifierRegistry axizIdentifierRegistry(ObjectProvider<IdentifierType> customTypes) {
    IdentifierRegistry.Builder builder = IdentifierRegistry.builder().discover();
    customTypes.orderedStream().forEach(builder::register);
    IdentifierRegistry registry = builder.build();
    IdentifierValidation.use(registry);
    return registry;
  }

  /** Runs before bean initialization, so clients never seed on an event loop. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveRandomConfiguration {
    @Bean
    static BeanFactoryPostProcessor axizReactiveRandomBootstrap() {
      return beanFactory -> ReactiveInfrastructureBootstrap.initialize();
    }
  }

  /** Money (de)serialization as {@code {"amount":"150.00","currency":"PEN"}}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
  static class JacksonConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "axizMoneyModule")
    MoneyJacksonModule axizMoneyModule() {
      return new MoneyJacksonModule();
    }
  }
}

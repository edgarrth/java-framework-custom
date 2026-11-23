package pe.axiz.spring.foundation;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.MonthDay;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.time.BusinessCalendar;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.core.time.DueDates;
import pe.axiz.core.time.TimeSettings;
import pe.axiz.spring.foundation.lifecycle.WorkerLifecycle;
import pe.axiz.spring.foundation.lifecycle.WorkerStatus;

/**
 * Reloj, calendario de negocio, generación de ids, registro de identificadores y soporte JSON de
 * Money.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@EnableConfigurationProperties(AxizFoundationProperties.class)
public class AxizFoundationAutoConfiguration {

  @Bean
  SmartInitializingSingleton axizWorkerShutdownBudget(
      ObjectProvider<WorkerStatus> workers, Environment environment) {
    return () -> {
      Duration phase =
          Binder.get(environment)
              .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration.class)
              .orElse(Duration.ofSeconds(30));
      workers.forEach(
          worker -> {
            if (worker.shutdownGrace().plusSeconds(5).compareTo(phase) > 0)
              throw new IllegalStateException(
                  "Worker shutdown grace plus 5s must fit "
                      + "spring.lifecycle.timeout-per-shutdown-phase");
          });
    };
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
  static class WorkerHealthConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "axizWorkersHealthIndicator")
    HealthIndicator axizWorkersHealthIndicator(ObjectProvider<WorkerStatus> workers) {
      return () -> {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean ready = true;
        for (WorkerStatus worker : workers) {
          WorkerStatus.Snapshot snapshot = worker.workerSnapshot();
          ready &=
              snapshot.state() == WorkerLifecycle.State.RUNNING
                  && snapshot.expected() > 0
                  && snapshot.alive() == snapshot.expected();
          details.put(
              snapshot.name(),
              Map.of(
                  "state",
                  snapshot.state().name(),
                  "expected",
                  snapshot.expected(),
                  "alive",
                  snapshot.alive()));
        }
        return (ready ? Health.up() : Health.down()).withDetails(details).build();
      };
    }
  }

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
    return builder.build();
  }

  /**
   * Se ejecuta antes de la inicialización de los beans, de modo que los clientes nunca generan
   * semillas en un event loop.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveRandomConfiguration {
    @Bean
    static BeanFactoryPostProcessor axizReactiveRandomBootstrap() {
      return beanFactory -> ReactiveInfrastructureBootstrap.initialize();
    }
  }

  /** Serialización y deserialización de Money como {@code {"amount":"150.00","currency":"PEN"}}. */
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

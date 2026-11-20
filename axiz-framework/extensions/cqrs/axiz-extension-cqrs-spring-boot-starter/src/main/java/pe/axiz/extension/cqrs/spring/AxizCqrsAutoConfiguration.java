package pe.axiz.extension.cqrs.spring;

import jakarta.validation.Validator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.extension.cqrs.AccessPolicies;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Buses;
import pe.axiz.extension.cqrs.CommandBus;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.extension.cqrs.PipelineBehavior;
import pe.axiz.extension.cqrs.QueryBus;
import pe.axiz.extension.cqrs.QueryHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * CQRS buses over all {@link CommandHandler} and {@link QueryHandler} beans with the ordered
 * pipeline: observation, validation, authorization, idempotency, transaction, audit and metrics.
 * Each behavior is added only when its capability is present; duplicated handlers fail startup.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.idempotency.AxizIdempotencyAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditAutoConfiguration",
      "pe.axiz.spring.metrics.AxizMetricsAutoConfiguration",
      "pe.axiz.spring.observability.AxizObservabilityAutoConfiguration",
      "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration",
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
      "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
    })
public class AxizCqrsAutoConfiguration {

  /**
   * What to do at startup with commands and queries that nothing protects (no {@code
   * RequiresAuthority}, {@code AccessPolicy} or {@code OpenAccess}): {@code fail} (default), {@code
   * warn} (explicit warning-only policy) or {@code allow}. Unknown values reject startup.
   */
  public static final String UNPROTECTED_PROPERTY = "axiz.cqrs.authorization.unprotected";

  @Bean
  @ConditionalOnBean(ArchitectureObservation.class)
  ObservationBehavior axizCqrsObservationBehavior(ArchitectureObservation observation) {
    return new ObservationBehavior(observation);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "jakarta.validation.Validator")
  @ConditionalOnBean(Validator.class)
  static class ValidationConfiguration {
    @Bean
    ValidationBehavior axizCqrsValidationBehavior(Validator validator) {
      return new ValidationBehavior(validator);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
  static class AuthorizationConfiguration {
    @Bean
    AuthorizationBehavior axizCqrsAuthorizationBehavior(ObjectProvider<AccessPolicy<?>> policies) {
      return new AuthorizationBehavior(new AccessPolicies(policies.orderedStream().toList()));
    }
  }

  @Bean
  @ConditionalOnBean(IdempotencyService.class)
  IdempotencyBehavior axizCqrsIdempotencyBehavior(
      IdempotencyService service, ObjectProvider<JsonMapper> mapper) {
    return new IdempotencyBehavior(
        service, mapper.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build()));
  }

  @Bean
  @ConditionalOnBean(PlatformTransactionManager.class)
  TransactionBehavior axizCqrsTransactionBehavior(PlatformTransactionManager transactions) {
    return new TransactionBehavior(transactions);
  }

  @Bean
  @ConditionalOnBean(AuditTrail.class)
  AuditBehavior axizCqrsAuditBehavior(AuditTrail trail) {
    return new AuditBehavior(trail);
  }

  @Bean
  @ConditionalOnBean(EventMetrics.class)
  MetricsBehavior axizCqrsMetricsBehavior(EventMetrics metrics) {
    return new MetricsBehavior(metrics);
  }

  @Bean
  @ConditionalOnMissingBean
  Buses axizCqrsBuses(
      ObjectProvider<CommandHandler<?, ?>> commands,
      ObjectProvider<QueryHandler<?, ?>> queries,
      ObjectProvider<PipelineBehavior> behaviors,
      ObjectProvider<AccessPolicy<?>> policies,
      Environment environment) {
    List<CommandHandler<?, ?>> c = commands.orderedStream().toList();
    List<QueryHandler<?, ?>> q = queries.orderedStream().toList();
    List<Buses.Registration> registrations = HandlerRegistrations.of(c, q);
    AuthorizationCoverage.verify(
        registrations.stream().<Class<?>>map(Buses.Registration::requestType).toList(),
        new AccessPolicies(policies.orderedStream().toList()),
        environment.getProperty(UNPROTECTED_PROPERTY, "fail"));
    List<PipelineBehavior> pipeline = behaviors.orderedStream().toList();
    if (!registrations.isEmpty()
        && pipeline.stream().noneMatch(AuthorizationBehavior.class::isInstance))
      throw new IllegalStateException(
          "CQRS handlers require an AuthorizationBehavior and Spring Security");
    return new Buses(registrations, pipeline);
  }

  @Bean
  @ConditionalOnMissingBean
  CommandBus axizCommandBus(Buses buses) {
    return buses;
  }

  @Bean
  @ConditionalOnMissingBean
  QueryBus axizQueryBus(Buses buses) {
    return buses;
  }
}

package pe.axiz.framework.cqrs.spring;

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
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.framework.cqrs.AccessPolicies;
import pe.axiz.framework.cqrs.AccessPolicy;
import pe.axiz.framework.cqrs.Buses;
import pe.axiz.framework.cqrs.CommandHandler;
import pe.axiz.framework.cqrs.QueryHandler;
import pe.axiz.framework.cqrs.ReactiveAccessPolicy;
import pe.axiz.framework.cqrs.ReactiveCommandHandler;
import pe.axiz.framework.cqrs.ReactiveQueryHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reactive CQRS: {@link ReactiveCommandBus} and {@link ReactiveQueryBus} over all {@link
 * ReactiveCommandHandler} and {@link ReactiveQueryHandler} beans, with the same ordered pipeline as
 * the blocking buses built from non-blocking behaviors (the transaction is reactive, over the R2DBC
 * transaction manager). A request type with both a blocking and a reactive handler fails startup.
 */
@AutoConfiguration(after = AxizCqrsAutoConfiguration.class)
@ConditionalOnClass(name = "reactor.core.publisher.Mono")
public class AxizReactiveCqrsAutoConfiguration {

  @Bean
  @ConditionalOnBean(ArchitectureObservation.class)
  ReactiveBehaviors.Observation axizReactiveCqrsObservation(ArchitectureObservation observation) {
    return new ReactiveBehaviors.Observation(observation);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "jakarta.validation.Validator")
  @ConditionalOnBean(Validator.class)
  static class ValidationConfiguration {
    @Bean
    ReactiveBehaviors.Validation axizReactiveCqrsValidation(Validator validator) {
      return new ReactiveBehaviors.Validation(validator);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = "org.springframework.security.core.context.ReactiveSecurityContextHolder")
  static class AuthorizationConfiguration {
    @Bean
    ReactiveBehaviors.Authorization axizReactiveCqrsAuthorization(
        ObjectProvider<AccessPolicy<?>> policies,
        ObjectProvider<ReactiveAccessPolicy<?>> reactivePolicies) {
      return new ReactiveBehaviors.Authorization(
          new AccessPolicies(policies.orderedStream().toList()),
          reactivePolicies.orderedStream().toList());
    }
  }

  @Bean
  @ConditionalOnBean(IdempotencyService.class)
  ReactiveBehaviors.Idempotency axizReactiveCqrsIdempotency(
      IdempotencyService service, ObjectProvider<JsonMapper> mapper) {
    return new ReactiveBehaviors.Idempotency(
        service, mapper.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build()));
  }

  @Bean
  @ConditionalOnBean(ReactiveTransactionManager.class)
  ReactiveBehaviors.Transaction axizReactiveCqrsTransaction(ReactiveTransactionManager tx) {
    return new ReactiveBehaviors.Transaction(tx);
  }

  @Bean
  @ConditionalOnBean(AuditTrail.class)
  ReactiveBehaviors.Audit axizReactiveCqrsAudit(AuditTrail trail) {
    return new ReactiveBehaviors.Audit(trail);
  }

  @Bean
  @ConditionalOnBean(EventMetrics.class)
  ReactiveBehaviors.Metrics axizReactiveCqrsMetrics(EventMetrics metrics) {
    return new ReactiveBehaviors.Metrics(metrics);
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveBuses axizReactiveBuses(
      ObjectProvider<ReactiveCommandHandler<?, ?>> commands,
      ObjectProvider<ReactiveQueryHandler<?, ?>> queries,
      ObjectProvider<CommandHandler<?, ?>> blockingCommands,
      ObjectProvider<QueryHandler<?, ?>> blockingQueries,
      ObjectProvider<ReactivePipelineBehavior> behaviors,
      ObjectProvider<AccessPolicy<?>> policies,
      ObjectProvider<ReactiveAccessPolicy<?>> reactivePolicies,
      Environment environment) {
    List<ReactiveAccessPolicy<?>> nonBlocking = reactivePolicies.orderedStream().toList();
    List<ReactiveBuses.Registration> reactive =
        HandlerRegistrations.reactive(
            commands.orderedStream().toList(), queries.orderedStream().toList());
    List<Buses.Registration> blocking =
        HandlerRegistrations.of(
            blockingCommands.orderedStream().toList(), blockingQueries.orderedStream().toList());
    HandlerRegistrations.requireSingleStyle(blocking, reactive);
    AuthorizationCoverage.verify(
        reactive.stream()
            .<Class<?>>map(ReactiveBuses.Registration::requestType)
            .filter(t -> nonBlocking.stream().noneMatch(p -> p.requestType().isAssignableFrom(t)))
            .toList(),
        new AccessPolicies(policies.orderedStream().toList()),
        environment.getProperty(AxizCqrsAutoConfiguration.UNPROTECTED_PROPERTY, "warn"));
    return new ReactiveBuses(reactive, behaviors.orderedStream().toList());
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveCommandBus axizReactiveCommandBus(ReactiveBuses buses) {
    return buses;
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveQueryBus axizReactiveQueryBus(ReactiveBuses buses) {
    return buses;
  }
}

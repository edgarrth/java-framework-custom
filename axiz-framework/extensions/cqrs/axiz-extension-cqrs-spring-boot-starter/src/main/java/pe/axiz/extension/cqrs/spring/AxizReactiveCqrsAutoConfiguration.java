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
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.extension.cqrs.AccessPolicies;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.extension.cqrs.Buses;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.extension.cqrs.IdempotentCommand;
import pe.axiz.extension.cqrs.QueryHandler;
import pe.axiz.extension.cqrs.ReactiveAccessPolicy;
import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.extension.cqrs.ReactiveQueryHandler;
import pe.axiz.spring.audit.ReactiveAuditTrail;
import tools.jackson.databind.json.JsonMapper;

/**
 * CQRS reactivo: {@link ReactiveCommandBus} y {@link ReactiveQueryBus} sobre todos los beans {@link
 * ReactiveCommandHandler} y {@link ReactiveQueryHandler}, con el mismo pipeline ordenado que los
 * buses bloqueantes construido a partir de behaviors no bloqueantes (la transacción es reactiva,
 * sobre el gestor de transacciones R2DBC). Un tipo de petición con un handler bloqueante y otro
 * reactivo hace fallar el arranque.
 */
@AutoConfiguration(
    after = AxizCqrsAutoConfiguration.class,
    afterName = {
      "pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration",
      "pe.axiz.spring.idempotency.AxizIdempotencyAutoConfiguration"
    })
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
          AccessPolicies.none(), reactivePolicies.orderedStream().toList());
    }
  }

  @Bean
  @ConditionalOnBean(AsyncIdempotencyService.class)
  ReactiveBehaviors.Idempotency axizReactiveCqrsIdempotency(
      AsyncIdempotencyService service, ObjectProvider<JsonMapper> mapper) {
    return new ReactiveBehaviors.Idempotency(
        service, mapper.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build()));
  }

  @Bean
  @ConditionalOnBean(ReactiveTransactionManager.class)
  ReactiveBehaviors.Transaction axizReactiveCqrsTransaction(ReactiveTransactionManager tx) {
    return new ReactiveBehaviors.Transaction(tx);
  }

  @Bean
  @ConditionalOnBean(ReactiveAuditTrail.class)
  ReactiveBehaviors.Audit axizReactiveCqrsAudit(ReactiveAuditTrail trail) {
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
      ObjectProvider<AsyncIdempotencyService> nativeIdempotency,
      ObjectProvider<ReactiveAuditTrail> nativeAudit,
      Environment environment) {
    List<ReactiveAccessPolicy<?>> nonBlocking = reactivePolicies.orderedStream().toList();
    List<ReactiveBuses.Registration> reactive =
        HandlerRegistrations.reactive(
            commands.orderedStream().toList(), queries.orderedStream().toList());
    List<Buses.Registration> blocking =
        HandlerRegistrations.of(
            blockingCommands.orderedStream().toList(), blockingQueries.orderedStream().toList());
    HandlerRegistrations.requireSingleStyle(blocking, reactive);
    if (reactive.stream().anyMatch(r -> IdempotentCommand.class.isAssignableFrom(r.requestType()))
        && nativeIdempotency.getIfAvailable() == null)
      throw new IllegalStateException(
          "Reactive idempotent commands require AsyncIdempotencyService");
    if (reactive.stream().anyMatch(r -> AuditedCommand.class.isAssignableFrom(r.requestType()))
        && nativeAudit.getIfAvailable() == null)
      throw new IllegalStateException("Reactive audited commands require ReactiveAuditTrail");
    if (!reactive.isEmpty() && policies.stream().findAny().isPresent())
      throw new IllegalStateException(
          "Reactive CQRS requires ReactiveAccessPolicy; imperative access policies are not"
              + " supported");
    AuthorizationCoverage.verify(
        reactive.stream()
            .<Class<?>>map(ReactiveBuses.Registration::requestType)
            .filter(t -> nonBlocking.stream().noneMatch(p -> p.requestType().isAssignableFrom(t)))
            .toList(),
        AccessPolicies.none(),
        environment.getProperty(AxizCqrsAutoConfiguration.UNPROTECTED_PROPERTY, "fail"));
    List<ReactivePipelineBehavior> pipeline = behaviors.orderedStream().toList();
    if (!reactive.isEmpty()
        && pipeline.stream().noneMatch(ReactiveBehaviors.Authorization.class::isInstance))
      throw new IllegalStateException(
          "Reactive CQRS handlers require native authorization and Spring Security");
    return new ReactiveBuses(reactive, pipeline);
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

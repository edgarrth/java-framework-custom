package pe.axiz.spring.messaging;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.ConsumerRetryPolicy;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.EventRoutes;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.RoutingEventPublisher;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.spring.foundation.DispatchBoundary;
import pe.axiz.spring.foundation.InboxCompletion;
import pe.axiz.spring.foundation.ReactiveDispatchBoundary;
import pe.axiz.spring.foundation.ReactiveInboxCompletion;
import tools.jackson.databind.json.JsonMapper;

/**
 * CloudEvents messaging: envelope factory, JSON codec, observed publisher routed to the broker
 * adapters, and the dispatcher over all {@link EventHandler} beans with retries, dead-lettering and
 * de-duplication (inbox starter).
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
      "pe.axiz.spring.inbox.AxizInboxAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizMessagingProperties.class)
public class AxizMessagingAutoConfiguration {

  @Bean
  @ConditionalOnBooleanProperty(
      name = "axiz.messaging.consumer.transactional",
      matchIfMissing = true)
  static TransactionalEventHandlerPostProcessor axizTransactionalEventHandlers(
      ObjectProvider<PlatformTransactionManager> tx,
      ObjectProvider<InboxCompletion> completion,
      Environment environment) {
    return new TransactionalEventHandlerPostProcessor(
        tx, completion, environment.getProperty("axiz.inbox.transactional", "auto"));
  }

  /** Reactive handlers: transaction, context and timeout (reactive services). */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  static class ReactiveConfiguration {

    @Bean
    static ReactiveEventHandlerPostProcessor axizReactiveEventHandlers(
        ObjectProvider<ReactiveTransactionManager> tx,
        Environment env,
        ObjectProvider<ReactiveInboxCompletion> completion,
        ObjectProvider<ReactiveDispatchBoundary> boundaries) {
      // a static post-processor cannot take @ConfigurationProperties: bind the values with Spring
      // Boot's conversions ("100ms", "30s", relaxed names). Environment.getProperty(Duration) only
      // works when the environment carries Boot's conversion service, not in every context
      Binder binder = Binder.get(env);
      return new ReactiveEventHandlerPostProcessor(
          tx,
          binder.bind("axiz.messaging.consumer.transactional", Boolean.class).orElseGet(() -> true),
          binder
              .bind("axiz.messaging.consumer.handler-timeout", Duration.class)
              .orElseGet(() -> Duration.ofSeconds(30)),
          completion,
          env.getProperty("axiz.inbox.transactional", "auto"),
          boundaries);
    }
  }

  @Bean
  @ConditionalOnMissingBean
  EventRoutes axizEventRoutes(AxizMessagingProperties p) {
    return new EventRoutes(p.routes().defaultDestination(), p.routes().byType());
  }

  @Bean
  @ConditionalOnMissingBean
  PayloadCodec axizPayloadCodec(ObjectProvider<JsonMapper> mapper) {
    return new JacksonPayloadCodec(
        mapper.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build()));
  }

  @Bean
  @ConditionalOnMissingBean
  EventFactory axizEventFactory(
      AxizMessagingProperties p,
      Environment env,
      PayloadCodec codec,
      ObjectProvider<IdGenerator> ids,
      ObjectProvider<Clock> clock) {
    String source =
        p.source() != null
            ? p.source()
            : "urn:axiz:" + env.getProperty("spring.application.name", "application");
    return new EventFactory(
        source,
        p.typePrefix(),
        codec,
        ids.getIfAvailable(IdGenerator::uuidV7),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  EventPublisher axizEventPublisher(
      ObjectProvider<EventPublisherAdapter> adapters,
      ObjectProvider<ObservationRegistry> registry,
      AxizMessagingProperties p) {
    List<EventPublisher> list =
        adapters.orderedStream().map(EventPublisherAdapter::publisher).toList();
    return new ObservedEventPublisher(
        new RoutingEventPublisher(list),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        p);
  }

  @Bean
  @ConditionalOnMissingBean
  AxizEvents axizEvents(EventFactory factory, EventPublisher publisher) {
    return new AxizEvents(factory, publisher);
  }

  @Bean
  @ConditionalOnMissingBean
  EventDispatcher axizEventDispatcher(
      AxizMessagingProperties p,
      Environment env,
      ObjectProvider<EventHandler<?>> handlers,
      PayloadCodec codec,
      ObjectProvider<Deduplicator> deduplicator) {
    AxizMessagingProperties.Consumer c = p.consumer();
    String name =
        c.name() != null ? c.name() : env.getProperty("spring.application.name", "application");
    Deduplicator dedup =
        deduplicator.getIfAvailable(
            () -> {
              LoggerFactory.getLogger(AxizMessagingAutoConfiguration.class)
                  .info(
                      "No inbox configured: duplicate deliveries reach the handlers, which must be"
                          + " idempotent");
              return NoDeduplication.INSTANCE;
            });
    ConsumerRetryPolicy retry =
        new ConsumerRetryPolicy(
            c.maxAttempts(),
            Backoff.exponential(c.initialDelay(), c.maxDelay()),
            ConsumerRetryPolicy.defaults().transientFailure());
    EventDispatcher dispatcher =
        new EventDispatcher(
            name, handlers.orderedStream().toList(), codec, dedup, retry, c.ignoreUnknownTypes());
    dispatcher.verifySubscriptions(c.subscriptions());
    return dispatcher;
  }

  @Bean
  @ConditionalOnMissingBean
  ObservedDispatcher axizObservedDispatcher(
      EventDispatcher dispatcher,
      ObjectProvider<ObservationRegistry> registry,
      ObjectProvider<Clock> clock,
      ObjectProvider<DispatchBoundary> boundaries) {
    return new ObservedDispatcher(
        dispatcher,
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        clock.getIfAvailable(Clock::systemUTC),
        () -> {
          var scopes = new ArrayList<DispatchBoundary.Scope>();
          try {
            for (var boundary : boundaries.orderedStream().toList()) scopes.add(boundary.open());
          } catch (RuntimeException failure) {
            for (var scope : scopes.reversed()) {
              try {
                scope.close();
              } catch (RuntimeException close) {
                failure.addSuppressed(close);
              }
            }
            throw failure;
          }
          return () -> {
            RuntimeException failure = null;
            for (var scope : scopes.reversed()) {
              try {
                scope.close();
              } catch (RuntimeException close) {
                if (failure == null) failure = close;
                else failure.addSuppressed(close);
              }
            }
            if (failure != null) throw failure;
          };
        });
  }

  /** Accepts every delivery; used only when no inbox is configured. */
  enum NoDeduplication implements Deduplicator {
    INSTANCE;

    @Override
    public Claim claim(String consumer, String eventId) {
      return Claim.acquired(Claim.newToken());
    }

    @Override
    public boolean completed(String consumer, String eventId, String token) {
      return true; // nothing to record
    }

    @Override
    public boolean failed(String consumer, String eventId, String token) {
      return true; // nothing to record
    }
  }
}

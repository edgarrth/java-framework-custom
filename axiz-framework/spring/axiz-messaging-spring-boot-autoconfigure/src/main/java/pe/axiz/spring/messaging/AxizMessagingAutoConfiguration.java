package pe.axiz.spring.messaging;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.ConsumerRetryPolicy;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.RoutingEventPublisher;
import pe.axiz.core.resilience.Backoff;
import tools.jackson.databind.json.JsonMapper;

/**
 * CloudEvents messaging: envelope factory, JSON codec, observed publisher routed to the broker
 * adapters, and the dispatcher over all {@link EventHandler} beans with retries, dead-lettering and
 * de-duplication (inbox starter).
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
      "pe.axiz.spring.inbox.AxizInboxAutoConfiguration",
      "pe.axiz.spring.messaging.AxizMessagingKafkaAutoConfiguration",
      "pe.axiz.spring.messaging.AxizMessagingAwsAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizMessagingProperties.class)
public class AxizMessagingAutoConfiguration {

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
              org.slf4j.LoggerFactory.getLogger(AxizMessagingAutoConfiguration.class)
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
      ObjectProvider<Clock> clock) {
    return new ObservedDispatcher(
        dispatcher,
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        clock.getIfAvailable(Clock::systemUTC));
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

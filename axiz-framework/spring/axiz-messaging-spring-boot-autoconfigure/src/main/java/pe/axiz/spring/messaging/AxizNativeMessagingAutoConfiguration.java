package pe.axiz.spring.messaging;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.RoutingAsyncEventPublisher;
import pe.axiz.spring.foundation.ConfigurationErrors;

/**
 * Mensajes salientes nativos para flujos reactivos; los workers de sondeo conservan el dispatcher
 * imperativo.
 */
@AutoConfiguration(afterName = {"pe.axiz.spring.messaging.AxizMessagingAutoConfiguration"})
@ConditionalOnClass(name = "reactor.core.publisher.Mono")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizMessagingProperties.class)
public class AxizNativeMessagingAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  AsyncEventPublisher axizAsyncEventPublisher(
      ObjectProvider<AsyncEventPublisherAdapter> adapters,
      ObjectProvider<ObservationRegistry> observations,
      AxizMessagingProperties properties) {
    var routing =
        new RoutingAsyncEventPublisher(
            adapters.orderedStream().map(AsyncEventPublisherAdapter::publisher).toList());
    for (var destination : properties.destinations().entrySet()) {
      if (!routing.supports(destination.getValue()))
        throw new IllegalStateException(
            "No native messaging adapter supports configured "
                + ConfigurationErrors.destination(destination.getKey(), destination.getValue())
                + "; configure "
                + ConfigurationErrors.destinationProperty(
                    "axiz.messaging.destinations", destination.getKey()));
    }
    return new ObservedAsyncEventPublisher(
        routing, observations.getIfAvailable(() -> ObservationRegistry.NOOP), properties);
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveEvents axizReactiveEvents(EventFactory events, AsyncEventPublisher publisher) {
    return new ReactiveEvents(events, publisher);
  }
}

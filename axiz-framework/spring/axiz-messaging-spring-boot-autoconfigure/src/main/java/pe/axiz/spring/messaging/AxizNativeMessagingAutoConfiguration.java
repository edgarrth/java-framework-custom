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

/** Native outgoing messages for reactive flows; poll workers retain the imperative dispatcher. */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration",
      "pe.axiz.spring.messaging.AxizNativeMessagingAwsAutoConfiguration",
      "pe.axiz.spring.messaging.AxizMessagingKafkaAutoConfiguration"
    })
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
                + destinationProperty(destination.getKey()));
    }
    return new ObservedAsyncEventPublisher(
        routing, observations.getIfAvailable(() -> ObservationRegistry.NOOP), properties);
  }

  private static String destinationProperty(String logical) {
    return "axiz.messaging.destinations"
        + (logical.matches("[A-Za-z0-9_.-]+") ? "." + logical : " (see destination above)");
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveEvents axizReactiveEvents(EventFactory events, AsyncEventPublisher publisher) {
    return new ReactiveEvents(events, publisher);
  }
}

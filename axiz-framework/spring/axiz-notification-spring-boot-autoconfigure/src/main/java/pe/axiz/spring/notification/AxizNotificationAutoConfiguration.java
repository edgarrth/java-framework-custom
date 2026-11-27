package pe.axiz.spring.notification;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.notification.AsyncEventNotificationSender;
import pe.axiz.core.notification.AsyncNotificationSender;
import pe.axiz.core.notification.EventNotificationSender;
import pe.axiz.core.notification.NotificationSender;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Notificaciones (email, SMS, push) como eventos {@code notification.requested.v1} hacia el
 * servicio que las entrega; el destino es {@code axiz.notification.destination} (por defecto {@code
 * notifications}).
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration",
      "pe.axiz.spring.messaging.AxizNativeMessagingAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.notification.enabled", matchIfMissing = true)
@ConditionalOnBean(EventFactory.class)
public class AxizNotificationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  @ConditionalOnBean(EventPublisher.class)
  NotificationSender axizNotificationSender(
      EventFactory events, EventPublisher publisher, Environment env) {
    return new EventNotificationSender(
        events, publisher, env.getProperty("axiz.notification.destination", "notifications"));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class NativeConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AsyncNotificationSender axizAsyncNotificationSender(
        EventFactory events, AsyncEventPublisher publisher, Environment env) {
      return new AsyncEventNotificationSender(
          events, publisher, env.getProperty("axiz.notification.destination", "notifications"));
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveNotifications axizReactiveNotifications(AsyncNotificationSender sender) {
      return new ReactiveNotifications(sender);
    }
  }
}

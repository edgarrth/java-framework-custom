package pe.axiz.spring.notification;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.notification.EventNotificationSender;
import pe.axiz.core.notification.NotificationSender;

/**
 * Notifications (email, SMS, push) as {@code notification.requested.v1} events to the service that
 * delivers them; the destination is {@code axiz.notification.destination} (default {@code
 * notifications}).
 */
@AutoConfiguration(afterName = "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration")
@ConditionalOnBean({EventFactory.class, EventPublisher.class})
public class AxizNotificationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  NotificationSender axizNotificationSender(
      EventFactory events, EventPublisher publisher, Environment env) {
    return new EventNotificationSender(
        events, publisher, env.getProperty("axiz.notification.destination", "notifications"));
  }
}

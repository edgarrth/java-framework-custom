package pe.axiz.quality.archunit.sample.reactiveclean.application;

import java.util.concurrent.CompletionStage;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail.Context;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import pe.axiz.core.http.AccessToken;
import pe.axiz.core.http.AsyncAccessTokenProvider;
import pe.axiz.core.notification.AsyncNotificationSender;
import pe.axiz.core.notification.NotificationRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Compone publishers sin bloqueos ni hooks. */
public class Pipeline {
  public DataKey keyValue(DataKey value) {
    return value;
  }

  public Mono<Integer> doubled(Mono<Integer> value) {
    return value.map(v -> v * 2);
  }

  public Flux<String> names(Flux<Integer> ids) {
    return ids.map(String::valueOf);
  }

  public Context actor(Context value) {
    return value;
  }

  public CompletionStage<Void> audit(AsyncAuditEventPublisher publisher, AuditEvent event) {
    return publisher.publish(event);
  }

  public CompletionStage<AccessToken> token(AsyncAccessTokenProvider provider) {
    return provider.token();
  }

  public CompletionStage<Void> notifyRecipient(
      AsyncNotificationSender sender, NotificationRequest request) {
    return sender.send(request);
  }
}

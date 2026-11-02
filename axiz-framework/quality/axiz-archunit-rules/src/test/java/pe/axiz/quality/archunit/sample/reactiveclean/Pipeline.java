package pe.axiz.quality.archunit.sample.reactiveclean;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Composes publishers without blocking or hooks. */
public class Pipeline {
  public pe.axiz.core.crypto.DataKeyProvider.DataKey keyValue(
      pe.axiz.core.crypto.DataKeyProvider.DataKey value) {
    return value;
  }

  public Mono<Integer> doubled(Mono<Integer> value) {
    return value.map(v -> v * 2);
  }

  public Flux<String> names(Flux<Integer> ids) {
    return ids.map(String::valueOf);
  }

  public pe.axiz.core.audit.AuditTrail.Context actor(pe.axiz.core.audit.AuditTrail.Context value) {
    return value;
  }

  public java.util.concurrent.CompletionStage<Void> audit(
      pe.axiz.core.audit.AsyncAuditEventPublisher publisher, pe.axiz.core.audit.AuditEvent event) {
    return publisher.publish(event);
  }

  public java.util.concurrent.CompletionStage<pe.axiz.core.http.AccessToken> token(
      pe.axiz.core.http.AsyncAccessTokenProvider provider) {
    return provider.token();
  }

  public java.util.concurrent.CompletionStage<Void> notifyRecipient(
      pe.axiz.core.notification.AsyncNotificationSender sender,
      pe.axiz.core.notification.NotificationRequest request) {
    return sender.send(request);
  }
}

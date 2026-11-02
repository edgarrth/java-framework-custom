package pe.axiz.quality.archunit.sample.reactive;

import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

/** Synchronous capabilities must not enter a reactive application flow. */
public class BlockingCapabilities {
  public SecretValue secret(SecretResolver resolver) {
    return resolver.require("key");
  }

  public DataKeyProvider.DataKey key(DataKeyProvider provider) {
    return provider.generate("context");
  }

  public byte[] encrypt(EnvelopeEncryptor encryptor, byte[] value) {
    return encryptor.encrypt(value, "context");
  }

  public void audit(
      pe.axiz.core.audit.AuditPublisher publisher, pe.axiz.core.audit.AuditEvent event) {
    publisher.publish(event);
  }

  public void record(pe.axiz.core.audit.AuditTrail trail) {
    trail.record("action", "resource").success();
  }

  public pe.axiz.core.http.AccessToken token(pe.axiz.core.http.AccessTokenProvider provider) {
    return provider.token();
  }

  public void notifyRecipient(
      pe.axiz.core.notification.NotificationSender sender,
      pe.axiz.core.notification.NotificationRequest request) {
    sender.send(request);
  }
}

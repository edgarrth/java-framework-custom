package pe.axiz.quality.archunit.sample.reactive;

import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.http.AccessToken;
import pe.axiz.core.http.AccessTokenProvider;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.core.notification.NotificationSender;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

/** Las capacidades síncronas no deben entrar en un flujo de aplicación reactivo. */
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

  public void audit(AuditPublisher publisher, AuditEvent event) {
    publisher.publish(event);
  }

  public void record(AuditTrail trail) {
    trail.record("action", "resource").success();
  }

  public AccessToken token(AccessTokenProvider provider) {
    return provider.token();
  }

  public void notifyRecipient(NotificationSender sender, NotificationRequest request) {
    sender.send(request);
  }
}

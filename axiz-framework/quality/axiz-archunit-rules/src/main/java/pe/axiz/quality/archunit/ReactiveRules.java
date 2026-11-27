package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Set;

/** Sin E/S síncrona ni vías de escape mediante schedulers en el código de servicios reactivos. */
public final class ReactiveRules {
  private ReactiveRules() {}

  private static final Set<String> SYNCHRONOUS_PORTS =
      Set.of(
          "pe.axiz.core.cache.ByteCache",
          "pe.axiz.core.idempotency.IdempotencyStore",
          "pe.axiz.core.idempotency.IdempotencyService",
          "pe.axiz.core.lock.DistributedLock",
          "pe.axiz.core.lock.LockHandle",
          "pe.axiz.core.messaging.EventPublisher",
          "pe.axiz.core.sequence.SequenceStore",
          "pe.axiz.core.security.RateLimiter",
          "pe.axiz.core.security.NonceStore",
          "pe.axiz.core.storage.ObjectStorage",
          "pe.axiz.core.inbox.InboxStore",
          "pe.axiz.kit.dynamodb.DynamoRepository",
          "pe.axiz.core.secrets.SecretResolver",
          "pe.axiz.core.crypto.DataKeyProvider",
          "pe.axiz.core.crypto.EnvelopeEncryptor",
          "pe.axiz.core.audit.AuditPublisher",
          "pe.axiz.core.audit.AuditTrail",
          "pe.axiz.core.audit.AsyncAuditPublisher",
          "pe.axiz.core.http.AccessTokenProvider",
          "pe.axiz.adapter.http.jdk.ClientCredentialsTokenProvider",
          "pe.axiz.core.notification.NotificationSender",
          "pe.axiz.core.notification.EventNotificationSender",
          "pe.axiz.core.messaging.RoutingEventPublisher",
          "pe.axiz.spring.messaging.AxizEvents",
          "pe.axiz.spring.messaging.ObservedEventPublisher");

  public static List<ArchRule> rules() {
    return List.of(
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "jakarta.persistence..",
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "java.sql..",
                "pe.axiz.kit.jpa..",
                "pe.axiz.spring.saga..",
                "pe.axiz.spring.soap..",
                "org.springframework.web.client..")
            .because("reactive services use asynchronous I/O end to end"),
        noClasses()
            .should()
            .dependOnClassesThat(
                DescribedPredicate.describe(
                    "synchronous I/O ports or SDK clients",
                    type -> {
                      String name = type.getName();
                      return SYNCHRONOUS_PORTS.contains(name)
                          || (name.startsWith("software.amazon.awssdk.services.")
                              && name.endsWith("Client")
                              && !name.endsWith("AsyncClient"))
                          || name.equals(
                              "software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient");
                    }))
            .because("reactive services select native asynchronous ports and clients"),
        noClasses()
            .should()
            .callMethodWhere(
                DescribedPredicate.describe(
                    "blocking or scheduler offload",
                    call -> {
                      String owner = call.getTargetOwner().getName();
                      String name = call.getName();
                      return (owner.equals("reactor.core.scheduler.Schedulers")
                              && (name.equals("boundedElastic")
                                  || name.equals("newBoundedElastic")))
                          || ((owner.equals("java.util.concurrent.CompletableFuture")
                                  || owner.equals("java.util.concurrent.Future"))
                              && (name.equals("join") || name.equals("get")))
                          || ((owner.equals("reactor.core.publisher.Mono")
                                  || owner.equals("reactor.core.publisher.Flux"))
                              && Set.of(
                                      "block", "blockFirst", "blockLast", "toIterable", "toStream")
                                  .contains(name))
                          || (owner.startsWith("io.lettuce.core.") && name.equals("sync"))
                          || (owner.equals("java.net.http.HttpClient") && name.equals("send"))
                          || (owner.equals("java.lang.Thread") && name.equals("sleep"));
                    }))
            .because("blocking must not be hidden behind boundedElastic"));
  }
}

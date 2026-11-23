package pe.axiz.demo.protecteddata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import pe.axiz.adapter.crypto.kms.KmsDataKeyProvider;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultMaintenance;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.UncertainVaultMaintenance;
import pe.axiz.core.tokenization.vault.VaultMaintenanceAccess;
import pe.axiz.core.tokenization.vault.VaultMaintenanceAudit;
import pe.axiz.core.tokenization.vault.VaultMaintenanceBatch;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.core.tokenization.vault.VaultRetention;
import pe.axiz.core.tokenization.vault.VaultRotator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Worker administrativo separado: su identidad de escaneo y escritura de control nunca debe atender
 * a usuarios HTTP.
 */
final class VaultMaintenanceRecipe {
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final TokenType DNI = new TokenType("DNI");
  private static final TokenCaller ADMIN =
      new TokenCaller(
          "rotation-worker",
          "maintenance-job",
          "tenant-a",
          Set.of("SCOPE_tokens:rotate"),
          Set.of(DOMAIN));
  private static final VaultMaintenanceAccess ACCESS =
      (caller, action, keyset, scope) -> {
        if (!caller.subject().equals("rotation-worker")
            || !caller.clientId().equals("maintenance-job")
            || !keyset.equals("demo")
            || (scope != null && !scope.tenant().equals("tenant-a")))
          throw TokenErrors.failure(TokenErrors.DENIED);
      };

  private VaultMaintenanceRecipe() {}

  static void demonstrate(
      ApplicationContext oldWriter,
      ApplicationContextRunner runner,
      DynamoDbClient db,
      KmsClient kms,
      String oldArn,
      String nextArn,
      SqsClient sqs,
      String queue) {
    var maintenance = new DynamoDbVaultMaintenance(db, "demo-token-vault", Duration.ofSeconds(10));
    VaultMaintenanceAudit audit =
        event ->
            confirmed(
                sqs,
                queue,
                Map.of(
                    "action",
                    event.action().name(),
                    "result",
                    event.result().name(),
                    "operationId",
                    event.operationId().toString(),
                    "keyset",
                    event.keysetId(),
                    "version",
                    event.keyVersion(),
                    "tokenReference",
                    event.tokenReference()));
    var rotator = rotator(oldWriter, maintenance, audit, sqs, queue);
    var user = ProtectedDataExampleTest.caller("tenant-a");
    var before = oldWriter.getBean(VaultOperations.class);
    try (var value = SensitiveValue.of("00883344")) {
      var created = before.tokenize(user, DNI, value, DOMAIN, UUID.randomUUID().toString());
      var oldFingerprint = maintenance.keyState("demo");
      assertThrows(RuntimeException.class, () -> rotator.activate(user, oldFingerprint, "index2"));
      assertThat(maintenance.keyState("demo")).isEqualTo(oldFingerprint);
      assertThat(rotator.activate(ADMIN, oldFingerprint, "index2")).isTrue();
      var dualFingerprint = maintenance.keyState("demo");
      assertThrows(RuntimeException.class, () -> rotator.retire(ADMIN, dualFingerprint, "index"));
      walk(cursor -> rotator.reindex(ADMIN, dualFingerprint, cursor, 10));
      var plan = rotator.preparePrune(ADMIN, dualFingerprint, "index");
      walk(cursor -> rotator.prune(ADMIN, plan, cursor, 10));
      assertThat(rotator.retire(ADMIN, dualFingerprint, "index")).isTrue();
      assertThat(before.lookup(user, DNI, value, DOMAIN, "support")).contains(created.token());

      // La preparación de los lectores ocurre con anterioridad a este punto: ambas rutas inmutables
      // quedaron configuradas.
      // Se detiene la admisión y se drenan los escritores previo a la activación; no se reintenta
      // un escritor antiguo cercado (fenced).
      var targetKeys =
          new KeyringDataKeyProvider(
              Map.of(
                  "kek", new KmsDataKeyProvider(kms, oldArn),
                  "kek2", new KmsDataKeyProvider(kms, nextArn)),
              "kek2");
      var previousWrapping = maintenance.wrappingState("demo");
      assertThat(rotator.activateWrapping(ADMIN, previousWrapping, targetKeys)).isTrue();
      var dualWrapping = maintenance.wrappingState("demo");
      assertThrows(
          RuntimeException.class, () -> rotator.retireWrapping(ADMIN, dualWrapping, "kek"));
      try (var different = SensitiveValue.of("00998877")) {
        assertThrows(
            RuntimeException.class,
            () -> before.tokenize(user, DNI, different, DOMAIN, UUID.randomUUID().toString()));
      }
      // El despliegue reemplaza las instancias inmutables de escritura por otras con la clave B y
      // luego reanuda la admisión.
      runner
          .withPropertyValues(
              "axiz.crypto.kms.key-id=" + nextArn, "axiz.crypto.fields.index-current-key-id=index2")
          .run(
              newWriter -> {
                assertThat(newWriter).hasNotFailed();
                var operations = newWriter.getBean(VaultOperations.class);
                var current = rotator(newWriter, maintenance, audit, sqs, queue);
                var fingerprints = maintenance.keyState("demo");
                walk(
                    cursor ->
                        current.reencrypt(
                            ADMIN,
                            fingerprints,
                            cursor,
                            10,
                            newWriter.getBean(EnvelopeEncryptor.class)));
                assertThat(current.retireWrapping(ADMIN, dualWrapping, "kek")).isTrue();
                assertThat(maintenance.wrappingState("demo").readKeyIds())
                    .isEqualTo(List.of("kek2"));
                assertThat(
                        operations
                            .tokenize(user, DNI, value, DOMAIN, UUID.randomUUID().toString())
                            .token())
                    .isEqualTo(created.token());
                try (var clear =
                    operations.detokenize(user, DNI, created.token(), DOMAIN, "support")) {
                  assertThat(clear.reveal()).isEqualTo("00883344");
                }
                assertThat(operations.describe(user, DNI, created.token(), DOMAIN)).isPresent();

                // Solo avanza el reloj de la prueba: un worker de retención en producción usa UTC
                // confiable.
                var retention =
                    VaultRetention.builder()
                        .vault(maintenance)
                        .access(ACCESS)
                        .audit(audit)
                        .reconciliation(op -> reconcile(sqs, queue, op))
                        .alarm(
                            op -> {
                              throw new IllegalStateException(
                                  "Maintenance reconciliation requires an operator");
                            })
                        .clock(Clock.offset(Clock.systemUTC(), Duration.ofDays(31)))
                        .ids(UUID::randomUUID)
                        .authority("SCOPE_tokens:rotate")
                        .batchTimeout(Duration.ofSeconds(30))
                        .build();
                String cursor = null;
                int deleted = 0;
                int pages = 0;
                do {
                  if (++pages > 1000)
                    throw new IllegalStateException("Retention page budget exhausted");
                  var batch = retention.purge(ADMIN, fingerprints, cursor, 10);
                  deleted += batch.deleted();
                  cursor = batch.nextCursor();
                } while (cursor != null);
                assertThat(deleted).isPositive();
                assertThat(operations.lookup(user, DNI, value, DOMAIN, "support")).isEmpty();
              });
    }
    var messages = sqs.receiveMessage(b -> b.queueUrl(queue).maxNumberOfMessages(10)).messages();
    assertThat(messages).isNotEmpty();
    assertThat(messages.toString()).doesNotContain("00883344", "00998877");
  }

  private static VaultRotator rotator(
      ApplicationContext context,
      DynamoDbVaultMaintenance store,
      VaultMaintenanceAudit audit,
      SqsClient sqs,
      String queue) {
    return VaultRotator.builder()
        .vault(store)
        .indexes(context.getBean(BlindIndex.class))
        .source(context.getBean(EnvelopeEncryptor.class))
        .access(ACCESS)
        .audit(audit)
        .reconciliation(op -> reconcile(sqs, queue, op))
        .alarm(
            op -> {
              throw new IllegalStateException("Maintenance reconciliation requires an operator");
            })
        .clock(Clock.systemUTC())
        .ids(UUID::randomUUID)
        .authority("SCOPE_tokens:rotate")
        .maxProofPages(1000)
        .proofTimeout(Duration.ofMinutes(2))
        .build();
  }

  private static void walk(Function<String, VaultMaintenanceBatch> page) {
    String cursor = null;
    int pages = 0;
    do {
      if (++pages > 1000) throw new IllegalStateException("Migration requires another bounded run");
      var batch = page.apply(cursor);
      assertThat(batch.changed()).as("Concurrent changes require another traversal").isZero();
      cursor = batch.nextCursor();
    } while (cursor != null);
  }

  private static void reconcile(SqsClient sqs, String queue, UncertainVaultMaintenance operation) {
    var event = operation.event();
    var scope = event.scope();
    var token = operation.token();
    confirmed(
        sqs,
        queue,
        Map.of(
            "action",
            event.action().name(),
            "result",
            "UNCERTAIN",
            "operationId",
            event.operationId().toString(),
            "at",
            event.at().toString(),
            "keyset",
            event.keysetId(),
            "version",
            event.keyVersion(),
            "scope",
            scope == null ? "control" : scope.encoded(),
            "token",
            token == null ? "none" : token.reveal()));
  }

  private static void confirmed(SqsClient sqs, String queue, Map<String, ?> evidence) {
    var receipt =
        sqs.sendMessage(b -> b.queueUrl(queue).messageBody(JsonWriter.writeCanonical(evidence)));
    if (receipt.messageId() == null)
      throw new IllegalStateException("Maintenance evidence was not acknowledged");
  }
}

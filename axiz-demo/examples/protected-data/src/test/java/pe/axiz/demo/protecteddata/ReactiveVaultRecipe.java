package pe.axiz.demo.protecteddata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.adapter.audit.sqs.SqsAsyncAuditPublisher;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.AsyncTokenReconciliationLog;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.TokenAccessPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.core.tokenization.vault.TokenReconciliationAlarm;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.spring.audit.AsyncStrictAuditSink;
import pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration;
import pe.axiz.spring.crypto.ReactiveEnvelopeEncryption;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Native asynchronous vault providers. Only the test thread waits; no provider wraps a sync call.
 */
final class ReactiveVaultRecipe {
  private ReactiveVaultRecipe() {}

  static void demonstrate(
      List<String> properties,
      AwsCredentialsProvider identity,
      URI endpoint,
      Region region,
      String auditQueue,
      String reconciliationQueue) {
    try (var sqs =
        SqsAsyncClient.builder()
            .credentialsProvider(identity)
            .region(region)
            .endpointOverride(endpoint)
            .build()) {
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(ProtectedDataExampleTest.configurations())
          .withConfiguration(AutoConfigurations.of(AxizNativeAuditAutoConfiguration.class))
          .withPropertyValues(properties.toArray(String[]::new))
          .withBean(AwsCredentialsProvider.class, () -> identity)
          .withBean(
              AsyncStrictAuditSink.class,
              () -> new AsyncStrictAuditSink("sqs", new SqsAsyncAuditPublisher(sqs, auditQueue)))
          .withBean(
              TokenAccessPolicy.class,
              () ->
                  (caller, operation, type, domain, purpose) -> {
                    if (!caller.clientId().equals("customer-service"))
                      throw TokenErrors.failure(TokenErrors.DENIED);
                  })
          .withBean(
              AsyncTokenReconciliationLog.class,
              () ->
                  operation ->
                      sqs.sendMessage(
                              b ->
                                  b.queueUrl(reconciliationQueue)
                                      .messageBody(
                                          JsonWriter.writeCanonical(
                                              Map.of(
                                                  "id",
                                                  operation.operationId().toString(),
                                                  "scope",
                                                  operation.scope().encoded(),
                                                  "phase",
                                                  operation.phase().name(),
                                                  "reason",
                                                  operation.reason().name(),
                                                  "token",
                                                  Optional.ofNullable(operation.token())
                                                      .map(Token::reveal)
                                                      .orElse("none"),
                                                  "at",
                                                  operation.at().toString()))))
                          .thenAccept(
                              response -> {
                                if (response.messageId() == null)
                                  throw new IllegalStateException(
                                      "Reconciliation requires acknowledgement");
                              }))
          .withBean(
              TokenReconciliationAlarm.class,
              () ->
                  operation -> {
                    throw new IllegalStateException(
                        "Reconcile the durable operation before resuming");
                  })
          .withBean(
              TokenBudgetLeaseAlarm.class,
              () ->
                  (namespace, lease) -> {
                    throw new IllegalStateException("Token budget lease lost");
                  })
          .run(
              context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(VaultOperations.class);
                var encryption = context.getBean(ReactiveEnvelopeEncryption.class);
                byte[] clear = "synthetic protected statement".getBytes(StandardCharsets.UTF_8);
                byte[] cipher =
                    encryption.encrypt(clear, "statement:tenant-a").block(Duration.ofSeconds(10));
                assertThat(cipher).isNotEqualTo(clear);
                assertThat(
                        encryption
                            .decrypt(cipher, "statement:tenant-a")
                            .block(Duration.ofSeconds(10)))
                    .isEqualTo(clear);
                var fields = context.getBean(FieldEncryptor.class);
                var protectedField =
                    fields.encrypt("synthetic-reference", "statement.reference:tenant-a");
                assertThat(fields.decrypt(protectedField, "statement.reference:tenant-a"))
                    .isEqualTo("synthetic-reference");
                var vault = context.getBean(AsyncVaultOperations.class);
                var caller = ProtectedDataExampleTest.caller("tenant-a");
                var domain = new TokenDomain("payments");
                for (var entry : Map.of("DNI", "00332211", "PAN", "4111111111111111").entrySet()) {
                  var type = new TokenType(entry.getKey());
                  try (var input = SensitiveValue.of(entry.getValue())) {
                    var created =
                        await(
                            vault.tokenize(
                                caller, type, input, domain, UUID.randomUUID().toString()));
                    assertThat(created.created()).isTrue();
                    assertThat(await(vault.lookup(caller, type, input, domain, "support")))
                        .contains(created.token());
                    assertThat(
                            await(
                                vault.lookup(
                                    ProtectedDataExampleTest.caller("tenant-b"),
                                    type,
                                    input,
                                    domain,
                                    "support")))
                        .isEmpty();
                    assertThat(await(vault.describe(caller, type, created.token(), domain)))
                        .isPresent();
                    // The completion callback closes the sensitive result, including a late
                    // completion.
                    await(
                        vault
                            .detokenize(caller, type, created.token(), domain, "support")
                            .thenAccept(
                                revealed -> {
                                  try (revealed) {
                                    assertThat(revealed.reveal()).isEqualTo(entry.getValue());
                                  }
                                }));
                    assertThat(await(vault.delete(caller, type, created.token(), domain))).isTrue();
                    assertThat(await(vault.lookup(caller, type, input, domain, "support")))
                        .isEmpty();
                  }
                }
              });
    }
  }

  private static <T> T await(CompletionStage<T> operation) {
    return operation.toCompletableFuture().orTimeout(20, TimeUnit.SECONDS).join();
  }
}

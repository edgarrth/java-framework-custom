package pe.axiz.demo.protecteddata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.audit.sqs.SqsAuditPublisher;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultMaintenance;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.EncryptedValue;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.FieldProtectionPolicy;
import pe.axiz.core.crypto.FieldProtector;
import pe.axiz.core.crypto.FieldScope;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.TokenAccessPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.TokenReconciliationAlarm;
import pe.axiz.core.tokenization.vault.TokenReconciliationLog;
import pe.axiz.core.tokenization.vault.VaultOperations;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import pe.axiz.spring.audit.AxizAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizStrictAuditAutoConfiguration;
import pe.axiz.spring.audit.StrictAuditSink;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.crypto.AxizCryptoAutoConfiguration;
import pe.axiz.spring.crypto.kms.AxizCryptoKmsAutoConfiguration;
import pe.axiz.spring.secrets.AxizSecretsAutoConfiguration;
import pe.axiz.spring.secrets.aws.AxizSecretsAwsAutoConfiguration;
import pe.axiz.spring.test.AxizContainers;
import pe.axiz.spring.tokenization.pan.AxizTokenizationPanAutoConfiguration;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration;
import pe.axiz.spring.tokenization.vault.dynamodb.AxizTokenizationDynamoDbAutoConfiguration;
import pe.axiz.spring.tokenization.vault.redis.AxizTokenizationRedisAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Runnable infrastructure-backed examples. The test owns resources; the runtime only uses them. */
class ProtectedDataExampleTest {
  private static final String VAULT = "demo-token-vault";
  private static final String DOCUMENTS = "demo-customer-documents";
  private static final TokenDomain DOMAIN = new TokenDomain("payments");

  @Test
  void tokenizePanAndDniAndIndependentlyProtectSearchAndDecryptDni(TestReporter evidence) {
    String password = UUID.randomUUID().toString();
    try (var aws = AxizContainers.localstack();
        var redis =
            AxizContainers.valkey()
                .withCommand(
                    "valkey-server",
                    "--requirepass",
                    password,
                    "--maxmemory-policy",
                    "noeviction")) {
      aws.start();
      redis.start();
      var credentials =
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(aws.getAccessKey(), aws.getSecretKey()));
      var region = Region.of(aws.getRegion());
      try (var db =
              DynamoDbClient.builder()
                  .credentialsProvider(credentials)
                  .region(region)
                  .endpointOverride(aws.getEndpoint())
                  .httpClientBuilder(UrlConnectionHttpClient.builder())
                  .build();
          var kms =
              KmsClient.builder()
                  .credentialsProvider(credentials)
                  .region(region)
                  .endpointOverride(aws.getEndpoint())
                  .httpClientBuilder(UrlConnectionHttpClient.builder())
                  .build();
          var sqs =
              SqsClient.builder()
                  .credentialsProvider(credentials)
                  .region(region)
                  .endpointOverride(aws.getEndpoint())
                  .httpClientBuilder(UrlConnectionHttpClient.builder())
                  .build();
          var secrets =
              SecretsManagerClient.builder()
                  .credentialsProvider(credentials)
                  .region(region)
                  .endpointOverride(aws.getEndpoint())
                  .httpClientBuilder(UrlConnectionHttpClient.builder())
                  .build()) {
        provision(db);
        String keyArn = kms.createKey().keyMetadata().arn();
        String nextKeyArn = kms.createKey().keyMetadata().arn();
        String maintenanceQueue =
            sqs.createQueue(b -> b.queueName("demo-vault-maintenance")).queueUrl();
        String audit = sqs.createQueue(b -> b.queueName("demo-vault-audit")).queueUrl();
        String reconcile =
            sqs.createQueue(b -> b.queueName("demo-vault-reconciliation")).queueUrl();
        secret(
            secrets,
            "field-key-v1",
            Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded()));
        secret(
            secrets,
            "index-key-v1",
            Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded()));
        secret(
            secrets,
            "audit-key-v1",
            Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded()));
        secret(secrets, "redis-password", password);
        secret(
            secrets,
            "index-key-v2",
            Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded()));
        var properties =
            new ArrayList<>(
                List.of(
                    "spring.application.name=protected-data-example",
                    "axiz.config.environment=test",
                    "axiz.transport.allow-plain-http=true",
                    "axiz.transport.allow-insecure-data-stores=true",
                    "axiz.aws.endpoint=" + aws.getEndpoint(),
                    "axiz.aws.region=" + aws.getRegion(),
                    "axiz.secrets.secrets-manager.enabled=true",
                    "axiz.secrets.secrets-manager.prefix=demo/",
                    "axiz.crypto.fields.current-key-id=v1",
                    "axiz.crypto.fields.key-ids[0]=v1",
                    "axiz.crypto.fields.index-current-key-id=index",
                    "axiz.crypto.fields.index-secrets.index=index-key-v1",
                    "axiz.crypto.fields.index-secrets.index2=index-key-v2",
                    "axiz.crypto.kms.key-id=" + keyArn,
                    "axiz.crypto.kms.key-ring.kek=" + keyArn,
                    "axiz.crypto.kms.key-ring.kek2=" + nextKeyArn,
                    "axiz.audit.mode=SYNC",
                    "axiz.audit.strict.enabled=true",
                    "axiz.audit.strict.timeout=10s",
                    "axiz.audit.integrity.mode=HMAC",
                    "axiz.audit.integrity.hmac-secret=audit-key-v1",
                    "axiz.audit.integrity.hmac-key-id=audit-v1",
                    "axiz.tokenization.vault.dynamodb.table=" + VAULT,
                    "axiz.tokenization.vault.dynamodb.request-timeout=10s",
                    "axiz.tokenization.vault.auth.tenant-claim=tenant",
                    "axiz.tokenization.vault.auth.client-claim=azp",
                    "axiz.tokenization.vault.auth.domains-claim=domains",
                    "axiz.tokenization.vault.auth.authority-prefix=SCOPE_tokens:"));
        for (String type : List.of("DNI", "PAN")) {
          String p = "axiz.tokenization.vault.types." + type + ".";
          properties.addAll(
              List.of(
                  p + "profile=" + (type.equals("PAN") ? "pan.luhn" : "dni.pe"),
                  p + "version=1",
                  p + "keyset=demo",
                  p + "domains[0]=payments",
                  p + "purposes[0]=support",
                  p + "retention=30d"));
          if (type.equals("PAN")) properties.add(p + "metadata-keys[0]=last4");
        }
        budget(properties, redis.getHost(), redis.getMappedPort(6379));
        var runner =
            new ApplicationContextRunner()
                .withConfiguration(configurations())
                .withPropertyValues(properties.toArray(String[]::new))
                .withBean(AwsCredentialsProvider.class, () -> credentials)
                .withBean(
                    StrictAuditSink.class,
                    () -> new StrictAuditSink("sqs", new SqsAuditPublisher(sqs, audit)))
                .withBean(
                    TokenAccessPolicy.class,
                    () ->
                        (caller, operation, type, domain, purpose) -> {
                          if (!caller.clientId().equals("customer-service"))
                            throw TokenErrors.failure(TokenErrors.DENIED);
                        })
                .withBean(
                    TokenReconciliationLog.class,
                    () ->
                        operation -> {
                          var token = operation.token();
                          String body =
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
                                      token == null ? "none" : token.reveal(),
                                      "at",
                                      operation.at().toString()));
                          var sent = sqs.sendMessage(b -> b.queueUrl(reconcile).messageBody(body));
                          if (sent.messageId() == null)
                            throw new IllegalStateException("Reconciliation was not acknowledged");
                        })
                .withBean(
                    TokenReconciliationAlarm.class,
                    () ->
                        operation -> {
                          throw new IllegalStateException(
                              "Example requires reconciliation; inspect its durable queue");
                        })
                .withBean(
                    TokenBudgetLeaseAlarm.class,
                    () ->
                        (namespace, lease) -> {
                          throw new IllegalStateException("Example budget lease was lost");
                        });
        ReactiveVaultRecipe.demonstrate(
            properties, credentials, aws.getEndpoint(), region, audit, reconcile);
        recordVaultEvidence(evidence, "reactive");
        runner.run(
            context -> {
              assertThat(context).hasNotFailed();
              var envelopeEncryption = context.getBean(EnvelopeEncryptor.class);
              byte[] clear = "synthetic protected statement".getBytes(StandardCharsets.UTF_8);
              byte[] cipher = envelopeEncryption.encrypt(clear, "statement:tenant-a");
              assertThat(cipher).isNotEqualTo(clear);
              assertThat(envelopeEncryption.decrypt(cipher, "statement:tenant-a")).isEqualTo(clear);
              demonstrateTokens(context.getBean(VaultOperations.class), db);
              VaultMaintenanceRecipe.demonstrate(
                  context, runner, db, kms, keyArn, nextKeyArn, sqs, maintenanceQueue);
              demonstrateIndependentFields(
                  new FieldProtector(
                      context.getBean(FieldEncryptor.class),
                      context.getBean(BlindIndex.class),
                      FieldProtectionPolicy.dniPe("customer-search")),
                  db);
              var messages =
                  sqs.receiveMessage(
                          b -> b.queueUrl(audit).maxNumberOfMessages(10).waitTimeSeconds(1))
                      .messages();
              assertThat(messages).isNotEmpty();
              for (var message : messages) {
                var envelope = CloudEventJson.read(message.body());
                assertThat(envelope.type()).isEqualTo("pe.axiz.audit.event.v1");
                String body = new String(envelope.data(), StandardCharsets.UTF_8);
                assertThat(body).doesNotContain("4111111111111111", "00123456");
                assertThat(AuditEventJson.read(body).integrity()).isNotNull();
              }
              recordVaultEvidence(evidence, "servlet");
            });
      }
    }
  }

  private static void recordVaultEvidence(TestReporter evidence, String stack) {
    evidence.publishEntry("axiz.capability.crypto", stack);
    evidence.publishEntry("axiz.capability.crypto-kms", stack);
    evidence.publishEntry("axiz.capability.carddata", stack);
    evidence.publishEntry("axiz.capability.tokenization-vault", stack);
    evidence.publishEntry("axiz.capability.tokenization-vault-dynamodb", stack);
    evidence.publishEntry("axiz.capability.tokenization-vault-redis", stack);
    evidence.publishEntry("axiz.capability.tokenization-pan", stack);
  }

  static AutoConfigurations configurations() {
    return AutoConfigurations.of(
        AxizAwsIdentityAutoConfiguration.class,
        AxizSecretsAwsAutoConfiguration.class,
        AxizSecretsAutoConfiguration.class,
        AxizCryptoAutoConfiguration.class,
        AxizCryptoKmsAutoConfiguration.class,
        AxizAuditAutoConfiguration.class,
        AxizStrictAuditAutoConfiguration.class,
        AxizTokenizationVaultAutoConfiguration.class,
        AxizTokenizationDynamoDbAutoConfiguration.class,
        AxizTokenizationRedisAutoConfiguration.class,
        AxizTokenizationPanAutoConfiguration.class);
  }

  private static void secret(SecretsManagerClient secrets, String name, String value) {
    secrets.createSecret(b -> b.name("demo/" + name).secretString(value));
  }

  private static void provision(DynamoDbClient db) {
    createTable(
        db,
        b ->
            b.tableName(VAULT)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(
                    KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()));
    createTable(
        db,
        b ->
            b.tableName(DOCUMENTS)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    List.of("lookup", "rowId").stream()
                        .map(
                            name ->
                                AttributeDefinition.builder()
                                    .attributeName(name)
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                        .toList())
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName("lookup")
                        .keyType(KeyType.HASH)
                        .build(),
                    KeySchemaElement.builder()
                        .attributeName("rowId")
                        .keyType(KeyType.RANGE)
                        .build()));
    db.waiter().waitUntilTableExists(b -> b.tableName(VAULT));
    db.waiter().waitUntilTableExists(b -> b.tableName(DOCUMENTS));
    var maintenance = new DynamoDbVaultMaintenance(db, VAULT, Duration.ofSeconds(10));
    assertThat(
            maintenance.initialize(new FingerprintKeyState("demo", 1, "index", List.of("index"))))
        .isTrue();
    assertThat(
            maintenance.initializeWrapping(new WrappingKeyState("demo", 1, "kek", List.of("kek"))))
        .isTrue();
  }

  private static void createTable(
      DynamoDbClient db, java.util.function.Consumer<CreateTableRequest.Builder> configure) {
    var builder = CreateTableRequest.builder();
    configure.accept(builder);
    var request = builder.build();
    try {
      db.createTable(request);
    } catch (ResourceInUseException existing) {
      // A timed-out create may already have committed. Accept only the expected table shape.
      db.waiter().waitUntilTableExists(b -> b.tableName(request.tableName()));
      var table = db.describeTable(b -> b.tableName(request.tableName())).table();
      assertThat(table.keySchema()).containsExactlyElementsOf(request.keySchema());
      assertThat(table.attributeDefinitions())
          .containsExactlyInAnyOrderElementsOf(request.attributeDefinitions());
    }
  }

  static TokenCaller caller(String tenant) {
    return new TokenCaller(
        "demo-operator",
        "customer-service",
        tenant,
        Set.of(
            "SCOPE_tokens:tokenize",
            "SCOPE_tokens:lookup",
            "SCOPE_tokens:detokenize",
            "SCOPE_tokens:describe",
            "SCOPE_tokens:delete"),
        Set.of(DOMAIN));
  }

  private static void demonstrateTokens(VaultOperations vault, DynamoDbClient db) {
    for (var entry : Map.of("PAN", "4111111111111111", "DNI", "00123456").entrySet()) {
      var type = new TokenType(entry.getKey());
      try (var value = SensitiveValue.of(entry.getValue())) {
        var created =
            vault.tokenize(caller("tenant-a"), type, value, DOMAIN, UUID.randomUUID().toString());
        assertThat(created.created()).isTrue();
        assertThat(created.type()).isEqualTo(type);
        assertThat(created.toString()).doesNotContain(entry.getValue());
        assertThat(vault.lookup(caller("tenant-a"), type, value, DOMAIN, "support"))
            .contains(created.token());
        assertThat(vault.lookup(caller("tenant-b"), type, value, DOMAIN, "support")).isEmpty();
        try (var revealed =
            vault.detokenize(caller("tenant-a"), type, created.token(), DOMAIN, "support")) {
          assertThat(revealed.reveal()).isEqualTo(entry.getValue());
        }
        assertThat(db.scan(b -> b.tableName(VAULT)).items().toString())
            .doesNotContain(entry.getValue());
        assertThat(vault.delete(caller("tenant-a"), type, created.token(), DOMAIN)).isTrue();
        assertThat(vault.lookup(caller("tenant-a"), type, value, DOMAIN, "support")).isEmpty();
      }
    }
    try (var invalidPan = SensitiveValue.of("4111111111111112")) {
      assertThrows(
          RuntimeException.class,
          () ->
              vault.tokenize(
                  caller("tenant-a"),
                  new TokenType("PAN"),
                  invalidPan,
                  DOMAIN,
                  UUID.randomUUID().toString()));
    }
  }

  private static void demonstrateIndependentFields(FieldProtector fields, DynamoDbClient db) {
    var scope = new FieldScope("tenant-a", "customers");
    try (var original = SecretValue.of("00123456")) {
      var protectedField = fields.protect(original, scope, "row-1");
      db.putItem(
          b ->
              b.tableName(DOCUMENTS)
                  .item(
                      Map.of(
                          "lookup", AttributeValue.fromS(protectedField.index().encoded()),
                          "rowId", AttributeValue.fromS("row-1"),
                          "encryptedDni",
                              AttributeValue.fromS(protectedField.ciphertext().encoded()))));
      assertThat(fields.index(original, scope)).isEqualTo(protectedField.index());
      var matches =
          db.query(
                  b ->
                      b.tableName(DOCUMENTS)
                          .consistentRead(true)
                          .keyConditionExpression("#index = :index")
                          .expressionAttributeNames(Map.of("#index", "lookup"))
                          .expressionAttributeValues(
                              Map.of(
                                  ":index",
                                  AttributeValue.fromS(fields.index(original, scope).encoded()))))
              .items();
      assertThat(matches).hasSize(1);
      var stored = EncryptedValue.parse(matches.getFirst().get("encryptedDni").s());
      try (var plaintext = fields.decrypt(stored, scope, matches.getFirst().get("rowId").s())) {
        assertThat(plaintext.reveal()).isEqualTo("00123456");
      }
      assertThat(fields.candidates(original, scope)).contains(protectedField.index());
      assertThat(fields.index(original, new FieldScope("tenant-b", "customers")))
          .isNotEqualTo(protectedField.index());
      assertThrows(CryptoException.class, () -> fields.decrypt(stored, scope, "row-2"));
      assertThrows(
          CryptoException.class,
          () -> fields.decrypt(stored, new FieldScope("tenant-b", "customers"), "row-1"));
      assertThat(db.scan(b -> b.tableName(DOCUMENTS)).items().toString())
          .doesNotContain("00123456");
    }
  }

  private static void budget(List<String> properties, String host, int port) {
    String p = "axiz.tokenization.vault.redis.";
    properties.addAll(
        List.of(
            p + "namespace=protected-data-demo",
            p + "connection.host=" + host,
            p + "connection.port=" + port,
            p + "connection.tls=false",
            p + "connection.username=default",
            p + "connection.password-secret=redis-password",
            p + "connection.timeout=2s",
            p + "policy.subject-concurrent=10",
            p + "policy.client-concurrent=10",
            p + "policy.tenant-concurrent=10",
            p + "policy.concurrency-retry-after=1s"));
    for (String group : List.of("query", "reveal", "metadata", "writes"))
      for (String dimension : List.of("subject", "client", "tenant"))
        for (String window : List.of("burst", "minute", "day"))
          properties.add(p + "policy." + group + "." + dimension + "." + window + "=1000");
  }
}

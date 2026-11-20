package pe.axiz.quality.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.adapter.audit.sqs.SqsAsyncAuditPublisher;
import pe.axiz.adapter.audit.sqs.SqsAuditPublisher;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.crypto.kms.KmsAsyncDataKeyProvider;
import pe.axiz.adapter.crypto.kms.KmsDataKeyProvider;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbAsyncTokenVault;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbTokenVault;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultMaintenance;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyringAsyncDataKeyProvider;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.pan.PanTokenProfile;
import pe.axiz.core.tokenization.vault.AsyncTokenReconciliationLog;
import pe.axiz.core.tokenization.vault.AsyncTokenVault;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.TokenAccessPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.core.tokenization.vault.TokenReconciliationAlarm;
import pe.axiz.core.tokenization.vault.TokenReconciliationLog;
import pe.axiz.core.tokenization.vault.TokenVault;
import pe.axiz.core.tokenization.vault.UncertainTokenOperation;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.audit.AsyncStrictAuditSink;
import pe.axiz.spring.audit.AxizAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizStrictAuditAutoConfiguration;
import pe.axiz.spring.audit.StrictAuditSink;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.security.AxizSecurityAutoConfiguration;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration;
import pe.axiz.spring.tokenization.vault.http.AxizTokenizationVaultHttpAutoConfiguration;
import pe.axiz.spring.tokenization.vault.redis.AxizTokenizationRedisAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Signed JWT through real Spring HTTP/security/engine composition, local DynamoDB/KMS/SQS and
 * authenticated Valkey. Local HTTP fixtures do not attest production TLS, AWS IAM or PCI
 * compliance.
 */
@ExtendWith(BlockHoundExtension.class)
class VaultAcceptanceIT {
  private static final String PASSWORD = java.util.UUID.randomUUID().toString();
  private static final String SCOPES =
      "tokens:tokenize tokens:lookup tokens:describe tokens:detokenize tokens:delete";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static LocalStackContainer aws;
  private static GenericContainer<?> redis;
  private static DynamoDbClient db;
  private static DynamoDbAsyncClient asyncDb;
  private static KmsClient kms;
  private static KmsAsyncClient asyncKms;
  private static SqsClient sqs;
  private static SqsAsyncClient asyncSqs;
  private static AsyncAwsClients transports;
  private static HttpServer issuer;
  private static RSAKey signingKey;
  private static String keyArn;

  @BeforeAll
  static void start() throws Exception {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    aws = TestContainerImages.localstack().withServices("dynamodb", "kms", "sqs");
    aws.start();
    redis =
        TestContainerImages.valkey()
            .withExposedPorts(6379)
            .withCommand(
                "valkey-server", "--requirepass", PASSWORD, "--maxmemory-policy", "noeviction");
    redis.start();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(aws.getAccessKey(), aws.getSecretKey()));
    db =
        DynamoDbClient.builder()
            .credentialsProvider(credentials)
            .region(Region.of(aws.getRegion()))
            .endpointOverride(aws.getEndpoint())
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .overrideConfiguration(c -> c.apiCallTimeout(TIMEOUT))
            .build();
    kms =
        KmsClient.builder()
            .credentialsProvider(credentials)
            .region(Region.of(aws.getRegion()))
            .endpointOverride(aws.getEndpoint())
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .overrideConfiguration(c -> c.apiCallTimeout(TIMEOUT))
            .build();
    sqs =
        SqsClient.builder()
            .credentialsProvider(credentials)
            .region(Region.of(aws.getRegion()))
            .endpointOverride(aws.getEndpoint())
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .overrideConfiguration(c -> c.apiCallTimeout(TIMEOUT))
            .build();
    keyArn = kms.createKey().keyMetadata().arn();
    transports =
        new AsyncAwsClients(
            AwsCredentialSource.borrowed(credentials),
            Clock.systemUTC(),
            Duration.ofSeconds(1),
            AsyncAwsTransportSettings.defaults());
    asyncDb =
        transports.configured(
            DynamoDbAsyncClient.builder(), VaultAcceptanceIT::setting, "dynamodb");
    asyncKms = transports.configured(KmsAsyncClient.builder(), VaultAcceptanceIT::setting, "kms");
    asyncSqs = transports.configured(SqsAsyncClient.builder(), VaultAcceptanceIT::setting, "sqs");
    signingKey = new RSAKeyGenerator(2048).keyID("acceptance-key").generate();
    issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer.createContext(
        "/keys",
        exchange -> {
          byte[] keys =
              new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, keys.length);
          try (var body = exchange.getResponseBody()) {
            body.write(keys);
          }
        });
    issuer.start();
  }

  private static String setting(String key) {
    return switch (key) {
      case "axiz.aws.region" -> aws.getRegion();
      case "axiz.aws.endpoint" -> aws.getEndpoint().toString();
      case "axiz.config.environment" -> "test";
      case "axiz.transport.allow-plain-http" -> "true";
      default -> null;
    };
  }

  @AfterAll
  static void stop() {
    if (issuer != null) issuer.stop(0);
    if (asyncSqs != null) asyncSqs.close();
    if (asyncKms != null) asyncKms.close();
    if (asyncDb != null) asyncDb.close();
    if (transports != null) transports.close();
    if (sqs != null) sqs.close();
    if (kms != null) kms.close();
    if (db != null) db.close();
    if (redis != null) redis.stop();
    if (aws != null) aws.stop();
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class Servlet {}

  @Configuration(proxyBeanMethods = false)
  @EnableWebFlux
  static class Reactive {}

  private static AutoConfigurations configurations() {
    return AutoConfigurations.of(
        AxizSecurityAutoConfiguration.class,
        AxizAuditAutoConfiguration.class,
        AxizNativeAuditAutoConfiguration.class,
        AxizStrictAuditAutoConfiguration.class,
        AxizTokenizationVaultAutoConfiguration.class,
        AxizTokenizationVaultHttpAutoConfiguration.class,
        AxizTokenizationRedisAutoConfiguration.class);
  }

  private static String issuerUrl() {
    return "http://127.0.0.1:" + issuer.getAddress().getPort();
  }

  private static String bearer(String subject, String tenant, String scopes) throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("acceptance-key").build(),
            new JWTClaimsSet.Builder()
                .issuer(issuerUrl())
                .subject(subject)
                .audience("vault")
                .claim("azp", "vault-client")
                .claim("tenant", tenant)
                .claim("domains", List.of("payments"))
                .claim("scope", scopes)
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .build());
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private static final class Fixture implements AutoCloseable {
    final String id = "acceptance-" + UUID.randomUUID();
    final String table = id;
    final String queue = sqs.createQueue(b -> b.queueName(id)).queueUrl();
    final String reconciliation = sqs.createQueue(b -> b.queueName(id + "-reconcile")).queueUrl();
    final AtomicInteger alarms = new AtomicInteger();
    final BlindIndex indexes = new BlindIndex(Map.of("index", new byte[32]), "index");
    final EnvelopeEncryptor encryption =
        new EnvelopeEncryptor(
            new KeyringDataKeyProvider(Map.of("kek", new KmsDataKeyProvider(kms, keyArn)), "kek"),
            Duration.ofSeconds(30),
            100,
            Clock.systemUTC());
    final AsyncEnvelopeEncryptor nativeEncryption =
        new AsyncEnvelopeEncryptor(
            new KeyringAsyncDataKeyProvider(
                Map.of("kek", new KmsAsyncDataKeyProvider(asyncKms, keyArn)), "kek"),
            Duration.ofSeconds(30),
            100,
            Clock.systemUTC());
    final DynamoDbTokenVault vault = new DynamoDbTokenVault(db, table, TIMEOUT);
    final DynamoDbAsyncTokenVault nativeVault =
        new DynamoDbAsyncTokenVault(asyncDb, table, TIMEOUT);

    Fixture() {
      db.createTable(
          b ->
              b.tableName(table)
                  .billingMode(BillingMode.PAY_PER_REQUEST)
                  .attributeDefinitions(
                      AttributeDefinition.builder()
                          .attributeName("pk")
                          .attributeType(ScalarAttributeType.S)
                          .build())
                  .keySchema(
                      KeySchemaElement.builder()
                          .attributeName("pk")
                          .keyType(KeyType.HASH)
                          .build()));
      db.waiter().waitUntilTableExists(b -> b.tableName(table));
      var maintenance = new DynamoDbVaultMaintenance(db, table, TIMEOUT);
      assertTrue(maintenance.initialize(new FingerprintKeyState(id, 1, "index", List.of("index"))));
      assertTrue(
          maintenance.initializeWrapping(new WrappingKeyState(id, 1, "kek", List.of("kek"))));
    }

    SecretResolver secrets() {
      return name ->
          switch (name) {
            case "redis/password" -> Optional.of(SecretValue.of(PASSWORD));
            case "audit/hmac" ->
                Optional.of(SecretValue.of(Base64.getEncoder().encodeToString(new byte[64])));
            default -> Optional.empty();
          };
    }

    TokenAccessPolicy access() {
      return (caller, operation, type, domain, purpose) -> {
        if (!caller.tenant().startsWith("tenant-")) throw TokenErrors.failure(TokenErrors.DENIED);
      };
    }

    String[] settings(boolean reactive) {
      var values =
          new ArrayList<>(
              List.of(
                  "spring.application.name=token-vault-acceptance",
                  "axiz.config.environment=test",
                  "axiz.transport.allow-insecure-data-stores=true",
                  "axiz.tokenization.vault.http.allow-insecure=true",
                  "axiz.security.issuers[0].issuer=" + issuerUrl(),
                  "axiz.security.issuers[0].jwk-set-uri=" + issuerUrl() + "/keys",
                  "axiz.security.issuers[0].audiences[0]=vault",
                  "axiz.audit.mode=" + (reactive ? "ASYNC" : "SYNC"),
                  "axiz.audit.strict.enabled=true",
                  "axiz.audit.strict.timeout=10s",
                  "axiz.audit.integrity.mode=HMAC",
                  "axiz.audit.integrity.hmac-secret=audit/hmac",
                  "axiz.audit.integrity.hmac-key-id=audit-key",
                  "axiz.tokenization.vault.auth.tenant-claim=tenant",
                  "axiz.tokenization.vault.auth.client-claim=azp",
                  "axiz.tokenization.vault.auth.domains-claim=domains",
                  "axiz.tokenization.vault.auth.authority-prefix=SCOPE_tokens:"));
      for (String type : List.of("DNI", "PAN")) {
        String prefix = "axiz.tokenization.vault.types." + type + ".";
        values.addAll(
            List.of(
                prefix + "profile=" + (type.equals("DNI") ? "dni.pe" : "pan.luhn"),
                prefix + "version=1",
                prefix + "keyset=" + id,
                prefix + "domains[0]=payments",
                prefix + "purposes[0]=support",
                prefix + "retention=30d"));
        if (type.equals("PAN")) values.add(prefix + "metadata-keys[0]=last4");
      }
      String prefix = "axiz.tokenization.vault.redis.";
      values.addAll(
          List.of(
              prefix + "namespace=" + id,
              prefix + "connection.host=127.0.0.1",
              prefix + "connection.port=" + redis.getMappedPort(6379),
              prefix + "connection.tls=false",
              prefix + "connection.username=default",
              prefix + "connection.password-secret=redis/password",
              prefix + "connection.timeout=2s",
              prefix + "policy.subject-concurrent=20",
              prefix + "policy.client-concurrent=20",
              prefix + "policy.concurrency-retry-after=1s",
              prefix + "policy.tenant-concurrent=20"));
      for (String group : List.of("query", "reveal", "metadata", "writes"))
        for (String dimension : List.of("subject", "client", "tenant"))
          for (String window : List.of("burst", "minute", "day"))
            values.add(
                prefix
                    + "policy."
                    + group
                    + "."
                    + dimension
                    + "."
                    + window
                    + "="
                    + (group.equals("query") && dimension.equals("subject") ? "20" : "1000"));
      return values.toArray(String[]::new);
    }

    String reconciliationBody(UncertainTokenOperation operation) {
      return JsonWriter.writeCanonical(
          Map.of(
              "operationId",
              operation.operationId().toString(),
              "scope",
              operation.scope().encoded(),
              "phase",
              operation.phase().name(),
              "token",
              operation.token() == null ? "none" : operation.token().reveal(),
              "at",
              operation.at().toString(),
              "reason",
              operation.reason().name()));
    }

    @Override
    public void close() {
      encryption.close();
      nativeEncryption.close();
      indexes.close();
    }
  }

  @FunctionalInterface
  interface Http {
    Reply send(String route, String bearer, String body) throws Exception;
  }

  record Reply(int status, String body, String cache) {}

  @Test
  void servletComposesSignedIdentityEncryptedVaultDistributedBudgetAndDurableSealedAudit() {
    try (var f = new Fixture()) {
      new WebApplicationContextRunner()
          .withConfiguration(configurations())
          .withUserConfiguration(Servlet.class)
          .withPropertyValues(f.settings(false))
          .withBean(TokenProfile.class, PanTokenProfile::new)
          .withBean(SecretResolver.class, f::secrets)
          .withBean(TokenAccessPolicy.class, f::access)
          .withBean(BlindIndex.class, () -> f.indexes)
          .withBean(TokenVault.class, () -> f.vault)
          .withBean(EnvelopeEncryptor.class, () -> f.encryption)
          .withBean(
              StrictAuditSink.class,
              () -> new StrictAuditSink("sqs", new SqsAuditPublisher(sqs, f.queue)))
          .withBean(
              TokenBudgetLeaseAlarm.class, () -> (namespace, lease) -> f.alarms.incrementAndGet())
          .withBean(TokenReconciliationAlarm.class, () -> operation -> f.alarms.incrementAndGet())
          .withBean(
              TokenReconciliationLog.class,
              () ->
                  operation -> {
                    var response =
                        sqs.sendMessage(
                            b ->
                                b.queueUrl(f.reconciliation)
                                    .messageBody(f.reconciliationBody(operation)));
                    if (response.messageId() == null)
                      throw new IllegalStateException("Missing reconciliation acknowledgement");
                  })
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var mvc =
                    MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(
                            context.getBean("axizServletVaultGuard", Filter.class),
                            context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
                acceptance(
                    f,
                    (path, jwt, body) -> {
                      var request =
                          post(path).contentType(MediaType.APPLICATION_JSON).content(body);
                      if (!jwt.isEmpty()) request.header("Authorization", "Bearer " + jwt);
                      var response = mvc.perform(request).andReturn().getResponse();
                      return new Reply(
                          response.getStatus(),
                          response.getContentAsString(),
                          response.getHeader("Cache-Control"));
                    });
              });
    }
  }

  @Test
  void reactiveComposesTheSameContractsWithoutBlockingProviderBridges() {
    try (var f = new Fixture()) {
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(configurations())
          .withUserConfiguration(Reactive.class)
          .withPropertyValues(f.settings(true))
          .withBean(TokenProfile.class, PanTokenProfile::new)
          .withBean(SecretResolver.class, f::secrets)
          .withBean(TokenAccessPolicy.class, f::access)
          .withBean(BlindIndex.class, () -> f.indexes)
          .withBean(AsyncTokenVault.class, () -> f.nativeVault)
          .withBean(AsyncEnvelopeEncryptor.class, () -> f.nativeEncryption)
          .withBean(
              AsyncStrictAuditSink.class,
              () -> new AsyncStrictAuditSink("sqs", new SqsAsyncAuditPublisher(asyncSqs, f.queue)))
          .withBean(
              TokenBudgetLeaseAlarm.class, () -> (namespace, lease) -> f.alarms.incrementAndGet())
          .withBean(TokenReconciliationAlarm.class, () -> operation -> f.alarms.incrementAndGet())
          .withBean(
              AsyncTokenReconciliationLog.class,
              () ->
                  operation ->
                      asyncSqs
                          .sendMessage(
                              b ->
                                  b.queueUrl(f.reconciliation)
                                      .messageBody(f.reconciliationBody(operation)))
                          .thenAccept(
                              response -> {
                                if (response.messageId() == null)
                                  throw new IllegalStateException(
                                      "Missing reconciliation acknowledgement");
                              }))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var client =
                    WebTestClient.bindToApplicationContext(context)
                        .configureClient()
                        .responseTimeout(TIMEOUT)
                        .build();
                acceptance(
                    f,
                    (path, jwt, body) -> {
                      var request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
                      if (!jwt.isEmpty()) request.header("Authorization", "Bearer " + jwt);
                      var result = request.bodyValue(body).exchange().expectBody().returnResult();
                      return new Reply(
                          result.getStatus().value(),
                          new String(
                              Objects.requireNonNullElseGet(
                                  result.getResponseBody(), () -> new byte[0]),
                              StandardCharsets.UTF_8),
                          result.getResponseHeaders().getFirst("Cache-Control"));
                    });
              });
    }
  }

  private static String valueBody(String type, String value, boolean lookup) {
    return JsonWriter.writeCanonical(
        Map.of(
            "type",
            type,
            "value",
            value,
            "domain",
            "payments",
            lookup ? "purpose" : "operationId",
            lookup ? "support" : UUID.randomUUID().toString()));
  }

  private static String tokenBody(String type, String token, boolean reveal) {
    return reveal
        ? JsonWriter.writeCanonical(
            Map.of("type", type, "token", token, "domain", "payments", "purpose", "support"))
        : JsonWriter.writeCanonical(Map.of("type", type, "token", token, "domain", "payments"));
  }

  private static void expect(Reply reply, int status) {
    assertEquals(status, reply.status(), reply.body());
    assertEquals("no-store", reply.cache());
  }

  private static void acceptance(Fixture f, Http http) throws Exception {
    String allowed = bearer("alice", "tenant-a", SCOPES);
    String other = bearer("bob", "tenant-b", SCOPES);
    String reader = bearer("reader", "tenant-a", "tokens:describe");
    expect(http.send("/v1/tokens", "", valueBody("DNI", "87654321", false)), 401);
    expect(
        http.send("/v1/tokens", "invalid.jwt.signature", valueBody("DNI", "87654321", false)), 401);
    expect(http.send("/v1/tokens", reader, valueBody("DNI", "87654321", false)), 403);
    for (String type : List.of("DNI", "PAN")) {
      String value = type.equals("DNI") ? "87654321" : "4111111111111111";
      var missing = http.send("/v1/tokens/lookup", allowed, valueBody(type, value, true));
      expect(missing, 404);
      var created = http.send("/v1/tokens", allowed, valueBody(type, value, false));
      expect(created, 201);
      assertThat(created.body()).doesNotContain(value, "ciphertext", "fingerprint", "keyId");
      String token = (String) JsonReader.parseObject(created.body()).get("token");
      assertThat(token).matches("tok_[A-Za-z0-9]{22}");
      var duplicate = http.send("/v1/tokens", allowed, valueBody(type, value, false));
      expect(duplicate, 200);
      assertEquals(token, JsonReader.parseObject(duplicate.body()).get("token"));
      var found = http.send("/v1/tokens/lookup", allowed, valueBody(type, value, true));
      expect(found, 200);
      assertEquals(token, JsonReader.parseObject(found.body()).get("token"));
      expect(http.send("/v1/tokens/reveal", other, tokenBody(type, token, true)), 404);
      expect(http.send("/v1/tokens/reveal", reader, tokenBody(type, token, true)), 403);
      var revealed = http.send("/v1/tokens/reveal", allowed, tokenBody(type, token, true));
      expect(revealed, 200);
      assertEquals(value, JsonReader.parseObject(revealed.body()).get("value"));
      var otherCreated = http.send("/v1/tokens", other, valueBody(type, value, false));
      expect(otherCreated, 201);
      assertNotEquals(token, JsonReader.parseObject(otherCreated.body()).get("token"));
      String forged =
          valueBody(type, value, false)
              .replace("\"domain\":", "\"tenant\":\"tenant-b\",\"domain\":");
      expect(http.send("/v1/tokens", allowed, forged), 400);
      expect(http.send("/v1/tokens/delete", allowed, tokenBody(type, token, false)), 204);
      expect(http.send("/v1/tokens/lookup", allowed, valueBody(type, value, true)), 404);
      expect(http.send("/v1/tokens", allowed, valueBody(type, value, false)), 201);
    }
    String limited = bearer("limited", "tenant-a", SCOPES);
    int storedBeforeLookup = db.scan(b -> b.tableName(f.table).consistentRead(true)).count();
    for (int i = 0; i < 20; i++)
      expect(http.send("/v1/tokens/lookup", limited, valueBody("DNI", "00993344", true)), 404);
    expect(http.send("/v1/tokens/lookup", limited, valueBody("DNI", "00993344", true)), 429);
    assertEquals(
        storedBeforeLookup,
        db.scan(b -> b.tableName(f.table).consistentRead(true)).count(),
        "Lookup and admission failure cannot create token associations");
    for (var item : db.scan(b -> b.tableName(f.table).consistentRead(true)).items())
      assertThat(item.toString()).doesNotContain("87654321", "4111111111111111");
    int auditCount = 0;
    while (true) {
      var messages =
          sqs.receiveMessage(b -> b.queueUrl(f.queue).maxNumberOfMessages(10)).messages();
      if (messages.isEmpty()) break;
      for (var message : messages) {
        var event = JsonReader.parseObject(message.body());
        String body =
            new String(
                Base64.getDecoder().decode((String) event.get("data_base64")),
                StandardCharsets.UTF_8);
        assertThat(body).contains("integrity").doesNotContain("87654321", "4111111111111111");
        sqs.deleteMessage(b -> b.queueUrl(f.queue).receiptHandle(message.receiptHandle()));
        auditCount++;
      }
    }
    assertTrue(auditCount >= 20, "Operations must be durably acknowledged and sealed");
    sqs.deleteQueue(b -> b.queueUrl(f.queue));
    var denied = http.send("/v1/tokens", allowed, valueBody("DNI", "00883344", false));
    // Persistence committed, but durable audit acknowledgement failed: the overall operation
    // is uncertain. Preserve the association and require reconciliation, never a fresh replay.
    expect(denied, 504);
    assertThat(denied.body()).contains("AXZ-TOKEN-0504");
    assertThat(denied.body()).doesNotContain("00883344", "QueueDoesNotExist", "aws");
    var scope =
        new VaultScope(new TokenType("DNI"), "tenant-a", new TokenDomain("payments"), "dni.pe", 1);
    assertTrue(
        f.vault
            .findByFingerprint(
                scope, List.of(f.indexes.index("00883344", scope.indexScope(), "index")))
            .isPresent(),
        "A committed association survives audit failure; no unsafe compensating deletion");
    var committed =
        f.vault
            .findByFingerprint(
                scope, List.of(f.indexes.index("00883344", scope.indexScope(), "index")))
            .orElseThrow();
    int rowsBeforeRetry = db.scan(b -> b.tableName(f.table).consistentRead(true)).count();
    // A later request finds the existing association without writing. Its failed read audit is
    // unavailability, not a new uncertain mutation; it still cannot return the token.
    var retry = http.send("/v1/tokens", allowed, valueBody("DNI", "00883344", false));
    expect(retry, 503);
    assertThat(retry.body()).contains("AXZ-TOKEN-0503").doesNotContain(committed.token().reveal());
    assertEquals(rowsBeforeRetry, db.scan(b -> b.tableName(f.table).consistentRead(true)).count());
    var retried =
        f.vault
            .findByFingerprint(
                scope, List.of(f.indexes.index("00883344", scope.indexScope(), "index")))
            .orElseThrow();
    assertEquals(
        committed.token(),
        retried.token(),
        "A retry after failed audit must reuse the committed association");
    assertEquals(committed.revision(), retried.revision());
    assertEquals(committed.fingerprints(), retried.fingerprints());
    assertThat(retried.ciphertext()).containsExactly(committed.ciphertext());
    assertEquals(0, f.alarms.get());
  }
}

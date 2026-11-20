package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbAsyncTokenVault;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbTokenVault;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultMaintenance;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultReadiness;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbVaultReadiness.Keys;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.crypto.KeyringAsyncDataKeyProvider;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TextTokenProfiles;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.pan.PanTokenProfile;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.AsyncVaultTokenizer;
import pe.axiz.core.tokenization.vault.DeleteOutcome;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.MigrationOutcome;
import pe.axiz.core.tokenization.vault.PutOutcome;
import pe.axiz.core.tokenization.vault.SecureTokenGenerator;
import pe.axiz.core.tokenization.vault.TokenAuditEntry;
import pe.axiz.core.tokenization.vault.TokenBudget;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.TokenGenerator;
import pe.axiz.core.tokenization.vault.TokenTypePolicy;
import pe.axiz.core.tokenization.vault.UncertainTokenOperation;
import pe.axiz.core.tokenization.vault.UncertainVaultMaintenance;
import pe.axiz.core.tokenization.vault.VaultMaintenanceBatch;
import pe.axiz.core.tokenization.vault.VaultMaintenanceEvent;
import pe.axiz.core.tokenization.vault.VaultPolicy;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultRetention;
import pe.axiz.core.tokenization.vault.VaultRotator;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.core.tokenization.vault.VaultTokenizer;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Real local DynamoDB transactions. Test DEK/admission/audit doubles do not certify KMS, IAM or
 * PCI.
 */
@ExtendWith(BlockHoundExtension.class)
class LocalStackTokenVaultIT {
  private static final String TABLE = "axiz-token-vault";
  private static final TokenType DNI = new TokenType("DNI");
  private static final TokenType PAN = new TokenType("PAN");
  private static final TokenType TEXT = new TokenType("TEXT");
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static LocalStackContainer container;
  private static DynamoDbClient client;
  private static DynamoDbAsyncClient nativeClient;
  private static AsyncAwsClients resources;

  @Test
  void startupReadsRealSchemaAndAtomicKeyControlsWithoutCreatingMissingState() {
    try (var f = new Fixture()) {
      var readiness =
          new DynamoDbVaultReadiness(
              TABLE,
              REQUEST_TIMEOUT,
              new Keys(
                  Set.of(f.keyset),
                  f.indexes.keyIds(),
                  f.wrappingKeys.writeKeyId(),
                  f.wrappingKeys.readKeyIds()));
      readiness.verify(client);
      StepVerifier.create(
              Mono.defer(() -> Mono.fromCompletionStage(readiness.verify(nativeClient)))
                  .subscribeOn(Schedulers.parallel()))
          .expectComplete()
          .verify(Duration.ofSeconds(15));
      var unavailable =
          new DynamoDbVaultReadiness(
              TABLE,
              REQUEST_TIMEOUT,
              new Keys(
                  Set.of(f.keyset + "-absent"),
                  f.indexes.keyIds(),
                  f.wrappingKeys.writeKeyId(),
                  f.wrappingKeys.readKeyIds()));
      assertThrows(IllegalStateException.class, () -> unavailable.verify(client));
      StepVerifier.create(
              Mono.defer(() -> Mono.fromCompletionStage(unavailable.verify(nativeClient)))
                  .subscribeOn(Schedulers.parallel()))
          .expectError(IllegalStateException.class)
          .verify(Duration.ofSeconds(15));
      assertEquals(0, f.keys.decryptions.get());
    }
  }

  @BeforeAll
  static void start() {
    Docker.require(); // Do not resolve or start images before this check.
    ReactiveInfrastructureBootstrap.initialize();
    container = TestContainerImages.localstack().withServices("dynamodb");
    container.start();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey()));
    client =
        DynamoDbClient.builder()
            .endpointOverride(container.getEndpoint())
            .region(Region.of(container.getRegion()))
            .credentialsProvider(credentials)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    client.createTable(
        builder ->
            builder
                .tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(
                    KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()));
    client.waiter().waitUntilTableExists(builder -> builder.tableName(TABLE));
    resources =
        new AsyncAwsClients(
            AwsCredentialSource.borrowed(credentials),
            Clock.systemUTC(),
            Duration.ofSeconds(1),
            AsyncAwsTransportSettings.defaults());
    nativeClient =
        resources.configured(
            DynamoDbAsyncClient.builder(),
            key ->
                switch (key) {
                  case "axiz.aws.region" -> container.getRegion();
                  case "axiz.aws.endpoint" -> container.getEndpoint().toString();
                  case "axiz.config.environment" -> "test";
                  case "axiz.transport.allow-plain-http" -> "true";
                  default -> null;
                },
            "dynamodb");
  }

  @AfterAll
  static void stop() {
    if (nativeClient != null) nativeClient.close();
    if (resources != null) resources.close();
    if (client != null) client.close();
    if (container != null) container.stop();
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }

  static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  static AttributeValue n(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }

  static void control(String keyset, long version, String write, List<String> reads) {
    client.putItem(
        builder ->
            builder
                .tableName(TABLE)
                .item(
                    Map.of(
                        "pk",
                        s("C#" + keyset),
                        "schema",
                        n(1),
                        "keyset",
                        s(keyset),
                        "version",
                        n(version),
                        "writeKey",
                        s(write),
                        "readKeys",
                        AttributeValue.builder()
                            .l(reads.stream().map(LocalStackTokenVaultIT::s).toList())
                            .build())));
  }

  /** CPU test key source; the real envelope primitive still authenticates every stored value. */
  private static final class TestKeys implements DataKeyProvider {
    private final int marker;
    final AtomicInteger decryptions = new AtomicInteger();

    TestKeys() {
      this(7);
    }

    TestKeys(int marker) {
      this.marker = marker;
    }

    private byte[] raw() {
      byte[] result = new byte[32];
      Arrays.fill(result, (byte) marker);
      return result;
    }

    @Override
    public DataKey generate(String context) {
      byte[] value = raw();
      try {
        return new DataKey(value, new byte[] {(byte) marker});
      } finally {
        Arrays.fill(value, (byte) 0);
      }
    }

    @Override
    public byte[] decrypt(byte[] encrypted, String context) {
      decryptions.incrementAndGet();
      if (encrypted.length != 1 || encrypted[0] != (byte) marker)
        throw new CryptoException("Unknown test wrapping key");
      return raw();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final SecureTokenGenerator random = new SecureTokenGenerator();
    final String tenant = random.operationId().toString();
    final String keyset = "keys-" + tenant;
    final Clock clock = Clock.systemUTC();
    final BlindIndex indexes =
        new BlindIndex(Map.of("old", new byte[32], "new", new byte[64]), "old");
    final TestKeys keys = new TestKeys();
    final KeyringDataKeyProvider wrappingKeys =
        new KeyringDataKeyProvider(Map.of("dek-old", keys), "dek-old");
    final EnvelopeEncryptor encryption =
        new EnvelopeEncryptor(wrappingKeys, Duration.ofMinutes(1), 10000, clock);
    final AsyncEnvelopeEncryptor nativeEncryption =
        new AsyncEnvelopeEncryptor(
            new KeyringAsyncDataKeyProvider(
                Map.of(
                    "dek-old",
                    new AsyncDataKeyProvider() {
                      @Override
                      public CompletionStage<DataKey> generate(String context) {
                        return CompletableFuture.completedFuture(keys.generate(context));
                      }

                      @Override
                      public CompletionStage<byte[]> decrypt(byte[] encrypted, String context) {
                        return CompletableFuture.completedFuture(keys.decrypt(encrypted, context));
                      }
                    }),
                "dek-old"),
            Duration.ofMinutes(1),
            10000,
            clock);
    final DynamoDbTokenVault store = new DynamoDbTokenVault(client, TABLE, REQUEST_TIMEOUT);
    final DynamoDbAsyncTokenVault nativeStore =
        new DynamoDbAsyncTokenVault(nativeClient, TABLE, REQUEST_TIMEOUT);
    final List<TokenAuditEntry> audit = new CopyOnWriteArrayList<>();
    final List<UncertainTokenOperation> reconciliation = new CopyOnWriteArrayList<>();
    final AtomicInteger alarms = new AtomicInteger();
    final TokenCaller caller =
        new TokenCaller(
            "subject",
            "client",
            tenant,
            Set.of(
                "SCOPE_tokens:tokenize",
                "SCOPE_tokens:lookup",
                "SCOPE_tokens:describe",
                "SCOPE_tokens:detokenize",
                "SCOPE_tokens:delete"),
            Set.of(DOMAIN));
    final TokenBudget budget =
        new TokenBudget() {
          @Override
          public Permit acquire(TokenCaller c, TokenOperation operation, VaultScope scope) {
            return () -> {};
          }

          @Override
          public void beforeWrite(TokenCaller c, VaultScope scope) {}

          @Override
          public boolean distributed() {
            return false;
          }
        };
    final AsyncTokenBudget nativeBudget =
        new AsyncTokenBudget() {
          @Override
          public CompletionStage<Permit> acquire(
              TokenCaller c, TokenOperation operation, VaultScope scope) {
            return CompletableFuture.completedFuture(() -> CompletableFuture.completedFuture(null));
          }

          @Override
          public CompletionStage<Void> beforeWrite(TokenCaller c, VaultScope scope) {
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public boolean distributed() {
            return false;
          }
        };
    final VaultPolicy policy =
        new VaultPolicy(
            List.of(
                TokenTypePolicy.builder()
                    .type(DNI)
                    .profile(TextTokenProfiles.dniPe())
                    .keysetId(keyset)
                    .domains(Set.of(DOMAIN))
                    .purposes(Set.of("support"))
                    .retention(Duration.ofDays(30))
                    .metadataKeys(Set.of())
                    .panException(null)
                    .build(),
                TokenTypePolicy.builder()
                    .type(TEXT)
                    .profile(TextTokenProfiles.identity())
                    .keysetId(keyset)
                    .domains(Set.of(DOMAIN))
                    .purposes(Set.of("support"))
                    .retention(Duration.ofDays(30))
                    .metadataKeys(Set.of())
                    .panException(null)
                    .build(),
                TokenTypePolicy.builder()
                    .type(PAN)
                    .profile(new PanTokenProfile())
                    .keysetId(keyset)
                    .domains(Set.of(DOMAIN))
                    .purposes(Set.of("support"))
                    .retention(Duration.ofDays(30))
                    .metadataKeys(Set.of("last4"))
                    .panException(null)
                    .build()),
            (c, o, t, d, p) -> {},
            indexes,
            "SCOPE_tokens:",
            clock);
    final VaultTokenizer engine = sync(store, random);
    final AsyncVaultTokenizer nativeEngine = nativeEngine(nativeStore);

    final DynamoDbVaultMaintenance maintenance =
        new DynamoDbVaultMaintenance(client, TABLE, REQUEST_TIMEOUT);
    final List<VaultMaintenanceEvent> maintenanceEvents = new CopyOnWriteArrayList<>();
    final List<UncertainVaultMaintenance> maintenanceUncertain = new CopyOnWriteArrayList<>();
    final TokenCaller admin =
        new TokenCaller(
            "migration-service",
            "migration-client",
            tenant,
            Set.of("SCOPE_tokens:rotate"),
            Set.of(DOMAIN));
    boolean maintenanceAuditDown;
    boolean maintenanceLogDown;
    boolean denyMaintenanceScope;

    VaultRotator rotator(DynamoDbVaultMaintenance adapter) {
      return rotator(adapter, encryption);
    }

    VaultRotator rotator(DynamoDbVaultMaintenance adapter, EnvelopeEncryptor decryptor) {
      return VaultRotator.builder()
          .vault(adapter)
          .indexes(indexes)
          .source(decryptor)
          .access(
              (who, action, set, scope) -> {
                if (!set.equals(keyset)
                    || !who.subject().equals("migration-service")
                    || (scope != null && (!scope.tenant().equals(tenant) || denyMaintenanceScope)))
                  throw TokenErrors.failure(TokenErrors.DENIED);
              })
          .audit(
              event -> {
                if (maintenanceAuditDown)
                  throw new IllegalStateException("synthetic audit failure");
                maintenanceEvents.add(event);
              })
          .reconciliation(
              operation -> {
                if (maintenanceLogDown) throw new IllegalStateException("synthetic log failure");
                maintenanceUncertain.add(operation);
              })
          .alarm(operation -> alarms.incrementAndGet())
          .clock(clock)
          .ids(random::operationId)
          .authority("SCOPE_tokens:rotate")
          .maxProofPages(1000)
          .proofTimeout(Duration.ofMinutes(2))
          .build();
    }

    Fixture() {
      this(true);
    }

    Fixture(boolean wrapping) {
      assertTrue(maintenance.initialize(new FingerprintKeyState(keyset, 1, "old", List.of("old"))));
      if (wrapping)
        assertTrue(
            maintenance.initializeWrapping(
                new WrappingKeyState(keyset, 1, "dek-old", List.of("dek-old"))));
    }

    AsyncVaultTokenizer nativeEngine(DynamoDbAsyncTokenVault adapter) {
      return AsyncVaultTokenizer.builder()
          .policy(policy)
          .vault(adapter)
          .encryption(nativeEncryption)
          .tokens(random)
          .budget(nativeBudget)
          .audit(
              event -> {
                audit.add(event);
                return CompletableFuture.completedFuture(null);
              })
          .reconciliation(
              operation -> {
                reconciliation.add(operation);
                return CompletableFuture.completedFuture(null);
              })
          .alarm(operation -> alarms.incrementAndGet())
          .operationIds(random::operationId)
          .build();
    }

    VaultTokenizer sync(DynamoDbTokenVault store, TokenGenerator generator) {
      return sync(store, generator, encryption);
    }

    VaultTokenizer sync(
        DynamoDbTokenVault store, TokenGenerator generator, EnvelopeEncryptor encryptor) {
      return VaultTokenizer.builder()
          .policy(policy)
          .vault(store)
          .encryption(encryptor)
          .tokens(generator)
          .budget(budget)
          .audit(audit::add)
          .reconciliation(reconciliation::add)
          .alarm(operation -> alarms.incrementAndGet())
          .operationIds(random::operationId)
          .build();
    }

    VaultScope scope(TokenType type) {
      return new VaultScope(
          type,
          tenant,
          DOMAIN,
          type.equals(DNI) ? "dni.pe" : type.equals(PAN) ? "pan.luhn" : "text.identity",
          1);
    }

    TokenizationResult tokenize(String raw, TokenType type) {
      try (SensitiveValue value = SensitiveValue.of(raw)) {
        return engine.tokenize(caller, type, value, DOMAIN, random.operationId().toString());
      }
    }

    CompletableFuture<TokenizationResult> nativeTokenize(String raw) {
      try (SensitiveValue value = SensitiveValue.of(raw)) {
        return nativeEngine
            .tokenize(caller, DNI, value, DOMAIN, random.operationId().toString())
            .toCompletableFuture();
      }
    }

    @Override
    public void close() {
      nativeEncryption.close();
      encryption.close();
      indexes.close();
    }
  }

  @Test
  void retentionDeletesAssociationsAndReferencesAtomicallyWithoutDeletingARecreatedAssociation() {
    try (var f = new Fixture()) {
      var original = f.tokenize("00883344", DNI);
      var old = f.store.find(f.scope(DNI), original.token()).orElseThrow();
      int decryptions = f.keys.decryptions.get();
      var retention =
          VaultRetention.builder()
              .vault(f.maintenance)
              .access(
                  (who, action, keyset, scope) -> {
                    if (!keyset.equals(f.keyset)
                        || (scope != null && !scope.tenant().equals(f.tenant)))
                      throw TokenErrors.failure(TokenErrors.DENIED);
                  })
              .audit(f.maintenanceEvents::add)
              .reconciliation(f.maintenanceUncertain::add)
              .alarm(operation -> f.alarms.incrementAndGet())
              .clock(Clock.offset(f.clock, Duration.ofDays(31)))
              .ids(f.random::operationId)
              .authority("SCOPE_tokens:purge")
              .batchTimeout(Duration.ofSeconds(30))
              .build();
      var operator =
          new TokenCaller(
              "retention-service",
              "retention-client",
              f.tenant,
              Set.of("SCOPE_tokens:purge"),
              Set.of(DOMAIN));
      String cursor = null;
      int deleted = 0;
      int pages = 0;
      do {
        assertTrue(++pages <= 1000, "Retention traversal must terminate");
        var progress = retention.purge(operator, f.maintenance.keyState(f.keyset), cursor, 100);
        deleted += progress.deleted();
        cursor = progress.nextCursor();
      } while (cursor != null);
      assertEquals(1, deleted);
      assertEquals(decryptions, f.keys.decryptions.get());
      assertTrue(f.store.find(f.scope(DNI), original.token()).isEmpty());
      assertTrue(f.store.findByFingerprint(f.scope(DNI), old.fingerprints()).isEmpty());
      var recreated = f.tokenize("00883344", DNI);
      assertNotEquals(original.token(), recreated.token());
      assertEquals(DeleteOutcome.NOT_FOUND, f.maintenance.delete(old));
      assertEquals(
          recreated.token(),
          f.store.findByFingerprint(f.scope(DNI), old.fingerprints()).orElseThrow().token());
      assertTrue(f.maintenanceUncertain.isEmpty());
      assertEquals(0, f.alarms.get());
    }
  }

  @Test
  void oneAssociationUnderOneHundredCompetingSynchronousCalls() throws Exception {
    try (var fixture = new Fixture();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<TokenizationResult>> calls = new ArrayList<>();
      for (int i = 0; i < 100; i++)
        calls.add(executor.submit(() -> fixture.tokenize("00883344", DNI)));
      var tokens = new HashSet<Token>();
      int created = 0;
      for (var call : calls) {
        var result = call.get();
        tokens.add(result.token());
        if (result.created()) created++;
      }
      assertEquals(1, tokens.size());
      assertEquals(1, created);
      assertEquals(100, fixture.audit.size());
      try (var revealed =
          fixture.engine.detokenize(
              fixture.caller, DNI, tokens.iterator().next(), DOMAIN, "support")) {
        assertEquals("00883344", revealed.reveal());
      }
    }
  }

  @Test
  void nativeTransactionsAndRevelationRunWithBlockHound() {
    try (var fixture = new Fixture()) {
      StepVerifier.create(
              Flux.range(0, 100)
                  .flatMap(i -> Mono.fromCompletionStage(fixture.nativeTokenize("00883344")), 16)
                  .collectList()
                  .flatMap(
                      results -> {
                        assertEquals(
                            1, results.stream().map(TokenizationResult::token).distinct().count());
                        assertEquals(
                            1, results.stream().filter(TokenizationResult::created).count());
                        return Mono.fromCompletionStage(
                            fixture.nativeEngine.detokenize(
                                fixture.caller,
                                DNI,
                                results.getFirst().token(),
                                DOMAIN,
                                "support"));
                      })
                  .subscribeOn(Schedulers.parallel()))
          .assertNext(
              value -> {
                try (value) {
                  assertEquals("00883344", value.reveal());
                }
              })
          .expectComplete()
          .verify(Duration.ofSeconds(40));
    }
  }

  @Test
  void profilesScopesAndReadOnlyLookupKeepTheirContractAgainstDynamoDb() {
    try (var fixture = new Fixture();
        var other = new Fixture()) {
      Token dni = fixture.tokenize("00883344", DNI).token();
      Token text = fixture.tokenize("00883344", TEXT).token();
      Token pan = fixture.tokenize("4111-1111-1111-1111", PAN).token();
      assertNotEquals(dni, text);
      assertNotEquals(dni, other.tokenize("00883344", DNI).token());
      assertTrue(other.engine.describe(other.caller, DNI, dni, DOMAIN).isEmpty());
      try (var value = fixture.engine.detokenize(fixture.caller, PAN, pan, DOMAIN, "support")) {
        assertEquals("4111111111111111", value.reveal());
      }
      assertEquals(
          Map.of("last4", "1111"),
          fixture.engine.describe(fixture.caller, PAN, pan, DOMAIN).orElseThrow().metadata());
      try (var absent = SensitiveValue.of("00112233");
          var original = SensitiveValue.of("00883344")) {
        assertTrue(fixture.engine.lookup(fixture.caller, DNI, absent, DOMAIN, "support").isEmpty());
        assertEquals(
            dni,
            fixture.engine.lookup(fixture.caller, DNI, original, DOMAIN, "support").orElseThrow());
      }
      assertEquals(
          "AXZ-TOKEN-0400",
          assertThrows(AxizException.class, () -> fixture.tokenize("4111111111111111", TEXT))
              .code()
              .value());
    }
  }

  @Test
  void lostAckAfterActualCommitIsReconciledAndDoesNotCreateAgain() {
    try (var fixture = new Fixture()) {
      var lostAckClient = spy(client);
      doAnswer(
              call -> {
                client.transactWriteItems(call.getArgument(0, TransactWriteItemsRequest.class));
                throw SdkClientException.create("synthetic lost acknowledgement after commit");
              })
          .when(lostAckClient)
          .transactWriteItems(any(TransactWriteItemsRequest.class));
      var engine =
          fixture.sync(
              new DynamoDbTokenVault(lostAckClient, TABLE, REQUEST_TIMEOUT), fixture.random);
      try (var input = SensitiveValue.of("00883344")) {
        assertEquals(
            "AXZ-TOKEN-0504",
            assertThrows(
                    AxizException.class,
                    () ->
                        engine.tokenize(
                            fixture.caller,
                            DNI,
                            input,
                            DOMAIN,
                            fixture.random.operationId().toString()))
                .code()
                .value());
      }
      assertEquals(1, fixture.reconciliation.size());
      var retried = fixture.tokenize("00883344", DNI);
      assertFalse(retried.created());
      assertEquals(fixture.reconciliation.getFirst().token(), retried.token());
      assertEquals(0, fixture.alarms.get());
    }
  }

  @Test
  void staleBarrierAndDelayedDeleteCannotOverwriteOrDeleteReplacement() {
    try (var fixture = new Fixture()) {
      Token token = fixture.tokenize("00883344", DNI).token();
      VaultRecord old = fixture.store.find(fixture.scope(DNI), token).orElseThrow();
      FingerprintKeyState state = fixture.store.keyState(fixture.keyset);
      control(fixture.keyset, 2, "new", List.of("new", "old"));
      assertInstanceOf(
          PutOutcome.StaleKeyState.class, fixture.store.putIfAbsent(old, state, List.of()));
      assertEquals(token, fixture.tokenize("00883344", DNI).token());
      assertEquals(DeleteOutcome.DELETED, fixture.store.delete(old));
      // Force the same random token to reproduce an ABA incarnation instead of relying on
      // probability.
      var fixedTokenEngine = fixture.sync(fixture.store, () -> token);
      try (var input = SensitiveValue.of("00883344")) {
        assertTrue(
            fixedTokenEngine
                .tokenize(
                    fixture.caller, DNI, input, DOMAIN, fixture.random.operationId().toString())
                .created());
      }
      assertEquals(DeleteOutcome.CHANGED, fixture.store.delete(old));
      VaultRecord current = fixture.store.find(fixture.scope(DNI), token).orElseThrow();
      assertEquals(DeleteOutcome.DELETED, fixture.store.delete(current));
      try (var input = SensitiveValue.of("00883344")) {
        assertTrue(
            fixedTokenEngine
                .tokenize(
                    fixture.caller, DNI, input, DOMAIN, fixture.random.operationId().toString())
                .created());
      }
      // Same revision, scope and fingerprints; different authenticated incarnation.
      assertEquals(DeleteOutcome.CHANGED, fixture.store.delete(current));
      try (var value = fixture.engine.detokenize(fixture.caller, DNI, token, DOMAIN, "support")) {
        assertEquals("00883344", value.reveal());
      }
      assertTrue(fixture.engine.delete(fixture.caller, DNI, token, DOMAIN));
      try (var input = SensitiveValue.of("00883344")) {
        assertTrue(fixture.engine.lookup(fixture.caller, DNI, input, DOMAIN, "support").isEmpty());
      }
    }
  }

  @Test
  void actualOrphanIsIntegrityFailureAndNeverAFalseAbsentResult() {
    try (var fixture = new Fixture()) {
      Token token = fixture.tokenize("00883344", DNI).token();
      client.deleteItem(
          builder -> builder.tableName(TABLE).key(Map.of("pk", s("T#" + token.reveal()))));
      try (var input = SensitiveValue.of("00883344")) {
        assertEquals(
            "AXZ-TOKEN-0503",
            assertThrows(
                    AxizException.class,
                    () -> fixture.engine.lookup(fixture.caller, DNI, input, DOMAIN, "support"))
                .code()
                .value());
      }
      assertEquals(TokenAuditEntry.Result.INTEGRITY_FAILURE, fixture.audit.getLast().result());
    }
  }

  @Test
  void forgedPersistedAssociationFailsForBothEnginesIncludingConflictWinner() {
    try (var f = new Fixture()) {
      Token source = f.tokenize("00883344", DNI).token();
      Token victim = f.tokenize("00883345", DNI).token();
      var sourceRow =
          client
              .getItem(
                  b ->
                      b.tableName(TABLE)
                          .key(Map.of("pk", s("T#" + source.reveal())))
                          .consistentRead(true))
              .item();
      client.updateItem(
          b ->
              b.tableName(TABLE)
                  .key(Map.of("pk", s("T#" + victim.reveal())))
                  .updateExpression("SET fingerprints = :hashes")
                  .expressionAttributeValues(Map.of(":hashes", sourceRow.get("fingerprints"))));
      var sourceRecord = f.store.find(f.scope(DNI), source).orElseThrow();
      for (var hash : sourceRecord.fingerprints()) {
        String fingerprint =
            "F#"
                + f.scope(DNI).encoded()
                + "#"
                + hash.keyId()
                + "#"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hash.digest());
        client.updateItem(
            b ->
                b.tableName(TABLE)
                    .key(Map.of("pk", s(fingerprint)))
                    .updateExpression("SET #token = :victim")
                    .expressionAttributeNames(Map.of("#token", "token"))
                    .expressionAttributeValues(Map.of(":victim", s(victim.reveal()))));
      }
      int before = f.audit.size();
      try (var input = SensitiveValue.of("00883344")) {
        assertEquals(
            "AXZ-TOKEN-0503",
            assertThrows(
                    AxizException.class,
                    () -> f.engine.lookup(f.caller, DNI, input, DOMAIN, "support"))
                .code()
                .value());
        assertEquals(
            "AXZ-TOKEN-0503",
            assertThrows(AxizException.class, () -> f.tokenize("00883344", DNI)).code().value());
        assertEquals(
            "AXZ-TOKEN-0503",
            assertThrows(
                    AxizException.class,
                    () -> f.engine.detokenize(f.caller, DNI, victim, DOMAIN, "support"))
                .code()
                .value());
        StepVerifier.create(
                Mono.fromCompletionStage(
                    f.nativeEngine.lookup(f.caller, DNI, input, DOMAIN, "support")))
            .expectErrorMatches(LocalStackTokenVaultIT::integrityFailure)
            .verify(REQUEST_TIMEOUT);
        StepVerifier.create(Mono.fromCompletionStage(f.nativeTokenize("00883344")))
            .expectErrorMatches(LocalStackTokenVaultIT::integrityFailure)
            .verify(REQUEST_TIMEOUT);
        StepVerifier.create(
                Mono.fromCompletionStage(
                    f.nativeEngine.detokenize(f.caller, DNI, victim, DOMAIN, "support")))
            .expectErrorMatches(LocalStackTokenVaultIT::integrityFailure)
            .verify(REQUEST_TIMEOUT);
        // Force a first read that precedes the competing write; real DynamoDB rejects the
        // duplicate.
        var racingStore = spy(f.store);
        doReturn(Optional.empty())
            .doCallRealMethod()
            .when(racingStore)
            .findByFingerprint(any(), any());
        var racingEngine = f.sync(racingStore, f.random);
        assertEquals(
            "AXZ-TOKEN-0503",
            assertThrows(
                    AxizException.class,
                    () ->
                        racingEngine.tokenize(
                            f.caller, DNI, input, DOMAIN, f.random.operationId().toString()))
                .code()
                .value());
        var racingNative = spy(f.nativeStore);
        doReturn(CompletableFuture.completedFuture(Optional.empty()))
            .doCallRealMethod()
            .when(racingNative)
            .findByFingerprint(any(), any());
        StepVerifier.create(
                Mono.fromCompletionStage(
                    f.nativeEngine(racingNative)
                        .tokenize(f.caller, DNI, input, DOMAIN, f.random.operationId().toString())))
            .expectErrorMatches(LocalStackTokenVaultIT::integrityFailure)
            .verify(REQUEST_TIMEOUT);
        verify(racingStore).putIfAbsent(any(), any(), any());
        verify(racingNative).putIfAbsent(any(), any(), any());
      }
      assertEquals(8, f.audit.size() - before);
      assertTrue(
          f.audit.subList(before, f.audit.size()).stream()
              .allMatch(event -> event.result() == TokenAuditEntry.Result.INTEGRITY_FAILURE));
    }
  }

  private static boolean integrityFailure(Throwable failure) {
    return failure instanceof AxizException error && error.code().value().equals("AXZ-TOKEN-0503");
  }

  static void walk(Function<String, VaultMaintenanceBatch> action) {
    String cursor = null;
    int pages = 0;
    do {
      assertTrue(++pages < 1000, "Bounded maintenance traversal");
      cursor = action.apply(cursor).nextCursor();
    } while (cursor != null);
  }

  static VaultRecord revised(VaultRecord row, List<KeyedHash> hashes) {
    return VaultRecord.builder()
        .token(row.token())
        .scope(row.scope())
        .keysetId(row.keysetId())
        .fingerprints(hashes)
        .revision(row.revision() + 1)
        .ciphertext(row.ciphertext())
        .createdAt(row.createdAt())
        .expiresAt(row.expiresAt())
        .metadata(row.metadata())
        .build();
  }

  @Test
  void governedRotationKeepsTokenAndDualReadThenBlocksPrematureRetirement() throws Exception {
    try (var f = new Fixture();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Token token = f.tokenize("00883344", DNI).token();
      var rotator = f.rotator(f.maintenance);
      var old = f.store.keyState(f.keyset);
      assertFalse(rotator.initialize(f.admin, f.keyset, "old"));
      assertTrue(rotator.activate(f.admin, old, "new"));
      assertFalse(rotator.activate(f.admin, old, "new"));
      var dual = f.store.keyState(f.keyset);
      assertEquals(List.of("new", "old"), dual.readKeyIds());
      assertThrows(AxizException.class, () -> rotator.preparePrune(f.admin, dual, "old"));
      assertThrows(AxizException.class, () -> rotator.retire(f.admin, dual, "old"));
      List<Future<TokenizationResult>> writes = new ArrayList<>();
      for (int i = 0; i < 100; i++) writes.add(executor.submit(() -> f.tokenize("00883344", DNI)));
      walk(cursor -> rotator.reindex(f.admin, dual, cursor, 5));
      for (var write : writes) assertEquals(token, write.get().token());
      var migrated = f.store.find(f.scope(DNI), token).orElseThrow();
      assertEquals(2, migrated.fingerprints().size());
      for (var hash : migrated.fingerprints())
        assertEquals(
            token, f.store.findByFingerprint(f.scope(DNI), List.of(hash)).orElseThrow().token());
      var plan = rotator.preparePrune(f.admin, dual, "old");
      walk(cursor -> rotator.prune(f.admin, plan, cursor, 5));
      var pruned = f.store.find(f.scope(DNI), token).orElseThrow();
      assertEquals(List.of("new"), pruned.fingerprints().stream().map(KeyedHash::keyId).toList());
      assertThrows(
          IllegalArgumentException.class,
          () -> f.maintenance.replace(pruned, revised(pruned, migrated.fingerprints()), dual));
      assertTrue(rotator.retire(f.admin, dual, "old"));
      assertFalse(rotator.activate(f.admin, f.store.keyState(f.keyset), "old"));
      assertEquals(List.of("new"), f.store.keyState(f.keyset).readKeyIds());
      assertThrows(AxizException.class, () -> rotator.prune(f.admin, plan, null, 5));
      assertEquals(token, f.tokenize("00883344", DNI).token());
      try (var value = f.engine.detokenize(f.caller, DNI, token, DOMAIN, "support")) {
        assertEquals("00883344", value.reveal());
      }
      assertTrue(
          f.maintenanceEvents.stream()
              .noneMatch(
                  event ->
                      event.toString().contains("00883344")
                          || event.toString().contains(token.reveal())));
    }
  }

  @Test
  void orphanReferenceAlsoBlocksRetirementAfterRecordTraversalIsEmpty() {
    try (var f = new Fixture()) {
      var token = f.tokenize("00883344", DNI).token();
      var rotator = f.rotator(f.maintenance);
      assertTrue(rotator.activate(f.admin, f.store.keyState(f.keyset), "new"));
      client.deleteItem(
          builder -> builder.tableName(TABLE).key(Map.of("pk", s("T#" + token.reveal()))));
      var dual = f.store.keyState(f.keyset);
      assertThrows(AxizException.class, () -> rotator.retire(f.admin, dual, "old"));
      assertEquals(dual, f.store.keyState(f.keyset));
      assertEquals(
          VaultMaintenanceEvent.Result.INTEGRITY_FAILURE, f.maintenanceEvents.getLast().result());
    }
  }

  @Test
  void reencryptUsesNewKeyWithoutChangingTokenOrFingerprints() {
    try (var f = new Fixture();
        var target =
            new EnvelopeEncryptor(
                new KeyringDataKeyProvider(
                    Map.of("dek-old", f.keys, "dek-new", new TestKeys(9)), "dek-new"),
                Duration.ofMinutes(1),
                10000,
                Clock.systemUTC())) {
      var token = f.tokenize("00883344", DNI).token();
      var before = f.store.find(f.scope(DNI), token).orElseThrow();
      var state = f.store.keyState(f.keyset);
      assertTrue(
          f.rotator(f.maintenance)
              .activateWrapping(
                  f.admin,
                  f.maintenance.wrappingState(f.keyset),
                  new KeyringDataKeyProvider(
                      Map.of("dek-old", f.keys, "dek-new", new TestKeys(9)), "dek-new")));
      walk(cursor -> f.rotator(f.maintenance).reencrypt(f.admin, state, cursor, 10, target));
      var after = f.store.find(f.scope(DNI), token).orElseThrow();
      assertEquals(before.fingerprints(), after.fingerprints());
      assertEquals(before.createdAt(), after.createdAt());
      assertEquals(before.expiresAt(), after.expiresAt());
      assertEquals(before.revision() + 1, after.revision());
      assertFalse(Arrays.equals(before.ciphertext(), after.ciphertext()));
      assertThrows(
          CryptoException.class,
          () ->
              f.encryption.decrypt(
                  after.ciphertext(), "purpose:token-vault", after.scope().binding(token)));
      byte[] plaintext =
          target.decrypt(after.ciphertext(), "purpose:token-vault", after.scope().binding(token));
      try {
        assertEquals("00883344", new String(plaintext, StandardCharsets.UTF_8));
      } finally {
        Arrays.fill(plaintext, (byte) 0);
      }
      assertEquals(
          "AXZ-TOKEN-0503",
          assertThrows(AxizException.class, () -> f.tokenize("00883344", DNI)).code().value());
      try (var value = SensitiveValue.of("00883344")) {
        assertEquals(
            token,
            f.sync(f.store, f.random, target)
                .tokenize(f.caller, DNI, value, DOMAIN, f.random.operationId().toString())
                .token());
      }
    }
  }

  @Test
  void maintenanceCannotOverwriteConcurrentDeleteRecreationOrStaleControl() {
    try (var f = new Fixture()) {
      var token = f.tokenize("00883344", DNI).token();
      var old = f.store.find(f.scope(DNI), token).orElseThrow();
      assertTrue(f.rotator(f.maintenance).activate(f.admin, f.store.keyState(f.keyset), "new"));
      var dual = f.store.keyState(f.keyset);
      var hashes = f.indexes.candidates("00883344", f.scope(DNI).indexScope());
      var updated = revised(old, hashes);
      assertEquals(MigrationOutcome.MIGRATED, f.maintenance.replace(old, updated, dual));
      assertEquals(MigrationOutcome.CHANGED, f.maintenance.replace(old, updated, dual));
      assertEquals(DeleteOutcome.DELETED, f.store.delete(updated));
      try (var value = SensitiveValue.of("00883344")) {
        assertTrue(
            f.sync(f.store, () -> token)
                .tokenize(f.caller, DNI, value, DOMAIN, f.random.operationId().toString())
                .created());
      }
      assertEquals(MigrationOutcome.CHANGED, f.maintenance.replace(old, updated, dual));
      assertEquals(token, f.tokenize("00883344", DNI).token());
      var recreated = f.store.find(f.scope(DNI), token).orElseThrow();
      control(f.keyset, dual.version() + 1, "new", List.of("new", "old"));
      assertEquals(
          MigrationOutcome.STALE_KEY_STATE,
          f.maintenance.replace(recreated, revised(recreated, recreated.fingerprints()), dual));
    }
  }

  @Test
  void maintenanceDenialAndMissingAuditStopBeforeDecryptionAndLostAckIsReconciled() {
    try (var f = new Fixture()) {
      f.tokenize("00883344", DNI);
      var rotator = f.rotator(f.maintenance);
      var initial = f.store.keyState(f.keyset);
      assertThrows(AxizException.class, () -> rotator.activate(f.caller, initial, "new"));
      assertThrows(AxizException.class, () -> rotator.activate(f.admin, initial, "missing-key"));
      assertEquals(initial, f.store.keyState(f.keyset));
      assertTrue(rotator.activate(f.admin, initial, "new"));
      var dual = f.store.keyState(f.keyset);
      f.denyMaintenanceScope = true;
      assertThrows(
          AxizException.class, () -> walk(cursor -> rotator.reindex(f.admin, dual, cursor, 100)));
      assertEquals(0, f.keys.decryptions.get());
      f.denyMaintenanceScope = false;
      f.maintenanceAuditDown = true;
      assertThrows(AxizException.class, () -> rotator.reindex(f.admin, dual, null, 100));
      assertEquals(0, f.keys.decryptions.get());
      f.maintenanceAuditDown = false;
      var broken = spy(client);
      var commits = new AtomicInteger();
      doAnswer(
              call -> {
                client.transactWriteItems(call.getArgument(0, TransactWriteItemsRequest.class));
                commits.incrementAndGet();
                throw SdkClientException.create("synthetic lost maintenance acknowledgement");
              })
          .when(broken)
          .transactWriteItems(any(TransactWriteItemsRequest.class));
      var uncertain = f.rotator(new DynamoDbVaultMaintenance(broken, TABLE, REQUEST_TIMEOUT));
      assertEquals(
          "AXZ-TOKEN-0504",
          assertThrows(
                  AxizException.class,
                  () -> walk(cursor -> uncertain.reindex(f.admin, dual, cursor, 100)))
              .code()
              .value());
      assertEquals(1, commits.get());
      assertEquals(1, f.maintenanceUncertain.size());
      assertEquals(
          VaultMaintenanceEvent.Action.REINDEX, f.maintenanceUncertain.getFirst().event().action());
      assertTrue(f.maintenanceUncertain.getFirst().token() != null);
      assertEquals(0, f.alarms.get());
      var plan = rotator.preparePrune(f.admin, dual, "old");
      walk(cursor -> rotator.prune(f.admin, plan, cursor, 100));
      f.maintenanceLogDown = true;
      assertEquals(
          "AXZ-TOKEN-0504",
          assertThrows(AxizException.class, () -> uncertain.retire(f.admin, dual, "old"))
              .code()
              .value());
      assertEquals(2, commits.get());
      assertEquals(1, f.alarms.get());
      assertEquals(List.of("new"), f.store.keyState(f.keyset).readKeyIds());
    }
  }

  @Test
  void wrappingControlFencesStaleWritersAndRetiresOnlyAfterAuthenticatedMigration() {
    try (var f = new Fixture();
        var target =
            new EnvelopeEncryptor(
                new KeyringDataKeyProvider(
                    Map.of("dek-old", f.keys, "dek-new", new TestKeys(9)), "dek-new"),
                Duration.ofMinutes(1),
                10000,
                f.clock)) {
      var token = f.tokenize("00883344", DNI).token();
      var original = f.store.find(f.scope(DNI), token).orElseThrow();
      var initial = f.maintenance.wrappingState(f.keyset);
      var nextKeys =
          new KeyringDataKeyProvider(
              Map.of("dek-old", f.keys, "dek-new", new TestKeys(9)), "dek-new");
      var rotator = f.rotator(f.maintenance);
      assertFalse(rotator.initializeWrapping(f.admin, f.keyset, f.wrappingKeys));
      assertTrue(rotator.activateWrapping(f.admin, initial, nextKeys));
      assertFalse(rotator.activateWrapping(f.admin, initial, nextKeys));
      var dual = f.maintenance.wrappingState(f.keyset);
      assertEquals(List.of("dek-new", "dek-old"), dual.readKeyIds());
      assertThrows(AxizException.class, () -> rotator.retireWrapping(f.admin, dual, "dek-old"));
      assertEquals(
          "AXZ-TOKEN-0503",
          assertThrows(AxizException.class, () -> f.tokenize("00995566", DNI)).code().value());
      StepVerifier.create(Mono.fromCompletionStage(f.nativeTokenize("00995566")))
          .expectErrorMatches(
              error ->
                  error instanceof AxizException failure
                      && failure.code().value().equals("AXZ-TOKEN-0503"))
          .verify();
      try (var absent = SensitiveValue.of("00995566")) {
        assertTrue(f.engine.lookup(f.caller, DNI, absent, DOMAIN, "support").isEmpty());
      }
      var hashState = f.store.keyState(f.keyset);
      walk(cursor -> rotator.reencrypt(f.admin, hashState, cursor, 5, target));
      var migrated = f.store.find(f.scope(DNI), token).orElseThrow();
      assertEquals("dek-new", KeyringDataKeyProvider.envelopeKeyId(migrated.ciphertext()));
      var delayedOldCiphertext =
          VaultRecord.builder()
              .token(migrated.token())
              .scope(migrated.scope())
              .keysetId(migrated.keysetId())
              .fingerprints(migrated.fingerprints())
              .revision(migrated.revision() + 1)
              .ciphertext(original.ciphertext())
              .createdAt(migrated.createdAt())
              .expiresAt(migrated.expiresAt())
              .metadata(migrated.metadata())
              .build();
      assertEquals(
          MigrationOutcome.STALE_KEY_STATE,
          f.maintenance.replace(migrated, delayedOldCiphertext, hashState));
      assertTrue(f.rotator(f.maintenance, target).retireWrapping(f.admin, dual, "dek-old"));
      assertFalse(
          rotator.activateWrapping(
              f.admin,
              f.maintenance.wrappingState(f.keyset),
              new KeyringDataKeyProvider(
                  Map.of("dek-old", f.keys, "dek-new", new TestKeys(9)), "dek-old")));
      assertEquals(List.of("dek-new"), f.maintenance.wrappingState(f.keyset).readKeyIds());
      try (var revealed =
          f.sync(f.store, f.random, target).detokenize(f.caller, DNI, token, DOMAIN, "support")) {
        assertEquals("00883344", revealed.reveal());
      }
      try (var value = SensitiveValue.of("00995566")) {
        assertTrue(
            f.sync(f.store, f.random, target)
                .tokenize(f.caller, DNI, value, DOMAIN, f.random.operationId().toString())
                .created());
      }
      assertEquals(
          MigrationOutcome.STALE_KEY_STATE,
          f.maintenance.replace(migrated, delayedOldCiphertext, hashState));
    }
  }

  @Test
  void missingWrappingControlFailsClosedAndCanOnlyBeInitializedExplicitly() {
    try (var f = new Fixture(false)) {
      assertThrows(AxizException.class, () -> f.tokenize("00883344", DNI));
      var rotator = f.rotator(f.maintenance);
      assertTrue(rotator.initializeWrapping(f.admin, f.keyset, f.wrappingKeys));
      assertFalse(rotator.initializeWrapping(f.admin, f.keyset, f.wrappingKeys));
      assertTrue(f.tokenize("00883344", DNI).created());
      client.deleteItem(builder -> builder.tableName(TABLE).key(Map.of("pk", s("E#" + f.keyset))));
      assertFalse(rotator.initializeWrapping(f.admin, f.keyset, f.wrappingKeys));
      assertFalse(
          rotator.initializeWrapping(
              f.admin,
              f.keyset,
              new KeyringDataKeyProvider(Map.of("dek-new", new TestKeys(9)), "dek-new")));
      assertThrows(AxizException.class, () -> f.tokenize("00995566", DNI));
      client.deleteItem(builder -> builder.tableName(TABLE).key(Map.of("pk", s("C#" + f.keyset))));
      assertFalse(rotator.initialize(f.admin, f.keyset, "new"));
    }
  }
}

package pe.axiz.payments.beneficiaries.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import pe.axiz.core.api.Page;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.payments.beneficiaries.model.NewBeneficiary;
import pe.axiz.payments.beneficiaries.service.BeneficiariesService;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import pe.axiz.spring.test.AxizContainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Two independent service instances, genuine Dynamo transactions and detached encrypted rows. */
@Timeout(180)
class BeneficiaryConcurrencyTest {
  private static final NewBeneficiary FIRST =
      new NewBeneficiary("Synthetic", "Synthetic Holder", "00219100123456789053", "PEN");
  private static final NewBeneficiary OTHER =
      new NewBeneficiary("Synthetic", "Synthetic Holder", "00319300987654321003", "PEN");
  private final AtomicInteger ids = new AtomicInteger();

  private BeneficiariesService service(
      BeneficiaryRepository repository, SimpleMeterRegistry metrics, int maximum) {
    return new BeneficiariesService(
        repository,
        IdentifierRegistry.withDiscoveredTypes(),
        () -> "B-" + ids.incrementAndGet(),
        new EventMetrics(metrics, MetricsSettings.defaults()),
        Clock.systemUTC(),
        new BeneficiaryPolicy(maximum));
  }

  @Test
  void transactionsEnforceUniquenessAndLimitsAndReadsObserveOtherReplicaRevocation(
      TestReporter evidence) throws Exception {
    var meters = new SimpleMeterRegistry();
    try (var local = AxizContainers.localstack()) {
      local.start();
      try (var client =
              DynamoDbClient.builder()
                  .endpointOverride(local.getEndpoint())
                  .region(Region.of(local.getRegion()))
                  .credentialsProvider(
                      StaticCredentialsProvider.create(
                          AwsBasicCredentials.create(local.getAccessKey(), local.getSecretKey())))
                  .build();
          var keys = new StaticKeyProvider("test", Map.of("test", AesGcm.newKey()))) {
        var enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
        enhanced
            .table("example-beneficiaries", TableSchema.fromBean(BeneficiaryItem.class))
            .createTable();
        client.waiter().waitUntilTableExists(r -> r.tableName("example-beneficiaries"));
        var repository =
            new DynamoBeneficiaryRepository(
                new DynamoRepositories(enhanced, "example-", () -> new CursorCodec(keys, false)),
                new FieldEncryptor(keys));
        race(repository, meters, "unique", FIRST, 50);
        race(repository, meters, "limit", OTHER, 1);
        var first = service(repository, meters, 50);
        var second = service(repository, meters, 50);
        var registered = first.create("revocation", FIRST);
        var detached = second.get("revocation", registered.getBeneficiaryId());
        first.remove("revocation", registered.getBeneficiaryId());
        assertFalse(
            detached.removed(), "SDK reads return independent objects, not a reference-cache fake");
        assertThrows(
            NotFoundException.class, () -> second.get("revocation", registered.getBeneficiaryId()));
        assertEquals(0, repository.allActive("revocation").size());
        assertNotNull(second.create("revocation", FIRST));
      }
    } finally {
      meters.close();
    }

    evidence.publishEntry("axiz.capability.dynamodb", "default");
  }

  private void race(
      DynamoBeneficiaryRepository repository,
      SimpleMeterRegistry meters,
      String customer,
      NewBeneficiary other,
      int maximum)
      throws Exception {
    var barrier = new CyclicBarrier(2);
    BeneficiaryRepository synchronizedStart =
        new BeneficiaryRepository() {
          public Optional<Beneficiary> findActive(String c, String id) {
            return repository.findActive(c, id);
          }

          public Page<Beneficiary> listActive(String c, int size, @Nullable String cursor) {
            return repository.listActive(c, size, cursor);
          }

          public List<Beneficiary> allActive(String c) {
            return repository.allActive(c);
          }

          public void insert(Beneficiary value, int limit) {
            try {
              barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            repository.insert(value, limit);
          }

          public Beneficiary update(Beneficiary value) {
            return repository.update(value);
          }
        };
    var a = service(synchronizedStart, meters, maximum);
    var b = service(synchronizedStart, meters, maximum);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var left = executor.submit(() -> create(a, customer, FIRST));
      var right = executor.submit(() -> create(b, customer, other));
      assertEquals(1, left.get(30, TimeUnit.SECONDS) + right.get(30, TimeUnit.SECONDS));
      assertEquals(1, repository.allActive(customer).size());
    }
  }

  private static int create(BeneficiariesService service, String customer, NewBeneficiary value) {
    try {
      service.create(customer, value);
      return 1;
    } catch (BusinessException rejected) {
      assertTrue(rejected.code().value().startsWith("PAYMENTS-BENEFICIARIES-"));
      return 0;
    }
  }
}

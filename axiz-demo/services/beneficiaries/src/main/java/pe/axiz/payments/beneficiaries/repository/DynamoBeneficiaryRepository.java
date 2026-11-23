package pe.axiz.payments.beneficiaries.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageMetadata;
import pe.axiz.core.crypto.EncryptedValue;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.kit.dynamodb.DynamoRepository;
import pe.axiz.kit.dynamodb.InvalidCursorException;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryErrors;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Implementación en DynamoDB sobre la tabla {@code beneficiaries} (con prefijo por entorno). Los
 * elementos eliminados se omiten con una expresión de filtro, por lo que una página puede contener
 * menos elementos que los solicitados mientras exista un cursor siguiente; los clientes siguen
 * {@code nextCursor} hasta que sea null.
 */
@Repository
class DynamoBeneficiaryRepository implements BeneficiaryRepository {

  static final String TABLE = "beneficiaries";
  private static final Expression ACTIVE =
      Expression.builder()
          .expression("attribute_not_exists(deletedAt) AND attribute_exists(cci)")
          .build();

  private final DynamoRepository<BeneficiaryItem> items;
  private final DynamoRepository<DirectoryVersion> directories;
  private final FieldEncryptor encryptor;
  private final CursorCodec cursors;
  private final String table;

  DynamoBeneficiaryRepository(DynamoRepositories repositories, FieldEncryptor encryptor) {
    this.items = repositories.create(TABLE, BeneficiaryItem.class);
    this.directories = repositories.create(TABLE, DirectoryVersion.class);
    this.encryptor = encryptor;
    this.cursors = repositories.cursors();
    this.table = repositories.tableName(TABLE);
  }

  @Override
  public Optional<Beneficiary> findActive(String customerId, String beneficiaryId) {
    return items
        .findById(customerId, beneficiaryId)
        .filter(b -> b.getDeletedAt() == null && b.getCci() != null)
        .map(this::restore);
  }

  @Override
  public Page<Beneficiary> listActive(String customerId, int size, @Nullable String cursor) {
    QueryEnhancedRequest.Builder request =
        QueryEnhancedRequest.builder()
            .queryConditional(partition(customerId))
            .consistentRead(true)
            .filterExpression(ACTIVE)
            .limit(size);
    Map<String, AttributeValue> start = startKey(customerId, cursor);
    if (start != null) {
      request.exclusiveStartKey(start);
    }
    var page = items.table().query(request.build()).iterator().next();
    return new Page<>(
        page.items().stream().map(this::restore).toList(),
        new PageMetadata(0, size, -1, -1),
        cursors.encode(page.lastEvaluatedKey(), binding(customerId)));
  }

  @Override
  public List<Beneficiary> allActive(String customerId) {
    List<Beneficiary> out = new ArrayList<>();
    items
        .table()
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(partition(customerId))
                .consistentRead(true)
                .filterExpression(ACTIVE)
                .build())
        .items()
        .forEach(item -> out.add(restore(item)));
    return out;
  }

  @Override
  public void insert(Beneficiary beneficiary, int maxPerCustomer) {
    var policy = new BeneficiaryPolicy(maxPerCustomer);
    String customer = beneficiary.getCustomerId();
    for (int attempt = 0; attempt < 8; attempt++) {
      long revision = directoryRevision(customer);
      List<Beneficiary> active = allActive(customer);
      // Ningún snapshot desactualizado puede rechazar una solicitud ni filtrarse a una transacción.
      if (directoryRevision(customer) != revision) continue;
      policy.checkRegistration(active, beneficiary.getCci());
      if (write(beneficiary, revision, true)) return;
    }
    throw conflict();
  }

  @Override
  public Beneficiary update(Beneficiary beneficiary) {
    String customer = beneficiary.getCustomerId();
    for (int attempt = 0; attempt < 8; attempt++) {
      long revision = directoryRevision(customer);
      var current =
          findActive(customer, beneficiary.getBeneficiaryId())
              .orElseThrow(DynamoBeneficiaryRepository::conflict);
      if (!Objects.equals(current.getVersion(), beneficiary.getVersion())
          || !current.sameAccount(beneficiary.getCci())) throw conflict();
      if (write(beneficiary, revision, false)) {
        beneficiary.setVersion(
            Math.incrementExact(Objects.requireNonNull(beneficiary.getVersion())));
        return beneficiary;
      }
    }
    throw conflict();
  }

  private long directoryRevision(String customer) {
    return directories
        .findById(customer, DirectoryVersion.KEY)
        .map(DirectoryVersion::getRevision)
        .orElse(0L);
  }

  /**
   * Cada cambio avanza la barrera del cliente de forma atómica junto con la fila versionada del
   * beneficiario.
   */
  private boolean write(Beneficiary beneficiary, long revision, boolean insert) {
    var fence =
        Expression.builder()
            .expression(revision == 0 ? "attribute_not_exists(#pk)" : "#revision = :expected")
            .expressionNames(
                revision == 0 ? Map.of("#pk", "customerId") : Map.of("#revision", "revision"))
            .expressionValues(
                revision == 0
                    ? Map.of()
                    : Map.of(":expected", AttributeValue.fromN(Long.toString(revision))))
            .build();
    var row =
        TransactPutItemEnhancedRequest.builder(BeneficiaryItem.class).item(store(beneficiary));
    if (insert)
      row.conditionExpression(
          Expression.builder()
              .expression("attribute_not_exists(#pk)")
              .expressionNames(Map.of("#pk", "customerId"))
              .build());
    try {
      items.transact(
          tx ->
              tx.addPutItem(
                      directories.table(),
                      TransactPutItemEnhancedRequest.builder(DirectoryVersion.class)
                          .item(DirectoryVersion.next(beneficiary.getCustomerId(), revision))
                          .conditionExpression(fence)
                          .build())
                  .addPutItem(items.table(), row.build()));
      return true;
    } catch (TransactionCanceledException cancelled) {
      var reasons = cancelled.cancellationReasons();
      if (reasons.size() == 2
          && ("ConditionalCheckFailed".equals(reasons.getFirst().code())
              || reasons.stream().anyMatch(reason -> "TransactionConflict".equals(reason.code()))))
        return false;
      throw conflict();
    }
  }

  private static BusinessException conflict() {
    return new BusinessException(
        CoreErrors.CONFLICT, "Beneficiary directory changed; reload before retrying");
  }

  static String accountBinding(String customer, String beneficiary) {
    return customer.length() + ":" + customer + beneficiary;
  }

  private BeneficiaryItem store(Beneficiary source) {
    var target = new BeneficiaryItem();
    target.setCustomerId(source.getCustomerId());
    target.setBeneficiaryId(source.getBeneficiaryId());
    target.setAlias(source.getAlias());
    target.setHolderName(source.getHolderName());
    target.setCurrency(source.getCurrency());
    target.setCreatedAt(source.getCreatedAt());
    target.setUpdatedAt(source.getUpdatedAt());
    target.setDeletedAt(source.getDeletedAt());
    target.setVersion(source.getVersion());
    target.setCci(
        EncryptedValue.parse(
            Objects.requireNonNull(
                encryptor.encrypt(
                    source.getCci(),
                    "beneficiaries.cci",
                    accountBinding(source.getCustomerId(), source.getBeneficiaryId())))));
    return target;
  }

  private Beneficiary restore(BeneficiaryItem source) {
    var target = new Beneficiary();
    target.setCustomerId(source.getCustomerId());
    target.setBeneficiaryId(source.getBeneficiaryId());
    target.setAlias(source.getAlias());
    target.setHolderName(source.getHolderName());
    target.setCurrency(source.getCurrency());
    target.setCreatedAt(source.getCreatedAt());
    target.setUpdatedAt(source.getUpdatedAt());
    target.setDeletedAt(source.getDeletedAt());
    target.setVersion(source.getVersion());
    target.setCci(
        Objects.requireNonNull(
            encryptor.decrypt(
                Objects.requireNonNull(source.getCci()).encoded(),
                "beneficiaries.cci",
                accountBinding(source.getCustomerId(), source.getBeneficiaryId()))));
    return target;
  }

  private static QueryConditional partition(String customerId) {
    return QueryConditional.keyEqualTo(Key.builder().partitionValue(customerId).build());
  }

  private String binding(String customerId) {
    return table + "/primary/active/customer=" + customerId;
  }

  /**
   * Decodifica el cursor y verifica que pertenezca al mismo cliente (sin lecturas entre
   * particiones).
   */
  private @Nullable Map<String, AttributeValue> startKey(
      String customerId, @Nullable String cursor) {
    Map<String, AttributeValue> start;
    try {
      start = cursors.decode(cursor, binding(customerId));
    } catch (InvalidCursorException _) {
      throw new BusinessException(BeneficiaryErrors.INVALID_CURSOR, "malformed cursor");
    }
    if (start == null) {
      return null;
    }
    AttributeValue owner = start.get("customerId");
    if (owner == null || !customerId.equals(owner.s()) || !start.containsKey("beneficiaryId")) {
      throw new BusinessException(BeneficiaryErrors.INVALID_CURSOR, "cursor of another partition");
    }
    return start;
  }
}

package pe.axiz.payments.beneficiaries.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.kit.dynamodb.DynamoRepository;
import pe.axiz.kit.dynamodb.InvalidCursorException;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryErrors;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * DynamoDB implementation on the {@code beneficiaries} table (prefixed per environment). Removed
 * items are skipped with a filter expression, so a page may hold fewer items than requested while a
 * next cursor exists; clients follow {@code nextCursor} until it is null.
 */
@Repository
class DynamoBeneficiaryRepository implements BeneficiaryRepository {

  static final String TABLE = "beneficiaries";
  private static final Expression ACTIVE =
      Expression.builder().expression("attribute_not_exists(deletedAt)").build();

  private final DynamoRepository<Beneficiary> items;
  private final CursorCodec cursors;
  private final String table;

  DynamoBeneficiaryRepository(DynamoRepositories repositories) {
    this.items = repositories.create(TABLE, Beneficiary.class);
    this.cursors = repositories.cursors();
    this.table = repositories.tableName(TABLE);
  }

  @Override
  public Optional<Beneficiary> findActive(String customerId, String beneficiaryId) {
    return items.findById(customerId, beneficiaryId).filter(b -> !b.removed());
  }

  @Override
  public Page<Beneficiary> listActive(String customerId, int size, @Nullable String cursor) {
    QueryEnhancedRequest.Builder request =
        QueryEnhancedRequest.builder()
            .queryConditional(partition(customerId))
            .filterExpression(ACTIVE)
            .limit(size);
    Map<String, AttributeValue> start = startKey(customerId, cursor);
    if (start != null) {
      request.exclusiveStartKey(start);
    }
    var page = items.table().query(request.build()).iterator().next();
    return new Page<>(
        page.items(),
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
                .filterExpression(ACTIVE)
                .build())
        .items()
        .forEach(out::add);
    return out;
  }

  @Override
  public void insert(Beneficiary beneficiary) {
    items.insert(beneficiary, "customerId");
  }

  @Override
  public Beneficiary update(Beneficiary beneficiary) {
    return items.update(beneficiary);
  }

  private static QueryConditional partition(String customerId) {
    return QueryConditional.keyEqualTo(Key.builder().partitionValue(customerId).build());
  }

  private String binding(String customerId) {
    return table + "/primary/active/customer=" + customerId;
  }

  /** Decodes the cursor and checks it belongs to the same customer (no cross-partition reads). */
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

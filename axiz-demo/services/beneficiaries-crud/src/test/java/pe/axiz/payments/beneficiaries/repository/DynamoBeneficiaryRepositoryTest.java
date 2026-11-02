package pe.axiz.payments.beneficiaries.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.Page;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryErrors;
import pe.axiz.payments.beneficiaries.model.NewBeneficiary;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/**
 * The DynamoDB repository over the real Enhanced Client and a scripted low-level client: the
 * requests it sends (table, key, filter of removed items, limit, start key) and how a cursor of
 * another customer is refused before any read.
 */
class DynamoBeneficiaryRepositoryTest {

  static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");

  /** Low-level client that records requests and answers from a script. */
  static final class Scripted implements DynamoDbClient {
    final List<Object> requests = new ArrayList<>();
    final Deque<QueryResponse> pages = new ArrayDeque<>();
    Map<String, AttributeValue> item = Map.of();

    @Override
    public GetItemResponse getItem(GetItemRequest r) {
      requests.add(r);
      return item.isEmpty()
          ? GetItemResponse.builder().build()
          : GetItemResponse.builder().item(item).build();
    }

    @Override
    public PutItemResponse putItem(PutItemRequest r) {
      requests.add(r);
      return PutItemResponse.builder().build();
    }

    @Override
    public UpdateItemResponse updateItem(UpdateItemRequest r) {
      requests.add(r);
      return UpdateItemResponse.builder().attributes(item).build();
    }

    @Override
    public QueryResponse query(QueryRequest r) {
      requests.add(r);
      QueryResponse page = pages.poll();
      return page != null ? page : QueryResponse.builder().items(List.of()).build();
    }

    @Override
    public String serviceName() {
      return "dynamodb";
    }

    @Override
    public void close() {}

    List<QueryRequest> queries() {
      return requests.stream()
          .filter(QueryRequest.class::isInstance)
          .map(QueryRequest.class::cast)
          .toList();
    }

    <R> R last(Class<R> type) {
      for (int i = requests.size() - 1; i >= 0; i--) {
        if (type.isInstance(requests.get(i))) {
          return type.cast(requests.get(i));
        }
      }
      throw new AssertionError("no " + type.getSimpleName() + " in " + requests);
    }
  }

  final Scripted dynamo = new Scripted();
  final DynamoBeneficiaryRepository repository =
      new DynamoBeneficiaryRepository(
          new DynamoRepositories(
              DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), "local-"));

  static Map<String, AttributeValue> row(String customer, String id, boolean removed) {
    Map<String, AttributeValue> row = new HashMap<>();
    row.put("customerId", AttributeValue.fromS(customer));
    row.put("beneficiaryId", AttributeValue.fromS(id));
    row.put("alias", AttributeValue.fromS("Landlord"));
    row.put("holderName", AttributeValue.fromS("Ana Torres"));
    row.put("cci", AttributeValue.fromS("00219100123456789053"));
    row.put("currency", AttributeValue.fromS("PEN"));
    row.put("createdAt", AttributeValue.fromS(NOW.toString()));
    row.put("updatedAt", AttributeValue.fromS(NOW.toString()));
    row.put("version", AttributeValue.fromN("1"));
    if (removed) {
      row.put("deletedAt", AttributeValue.fromS(NOW.toString()));
    }
    return row;
  }

  static Map<String, AttributeValue> key(String customer, String id) {
    return Map.of(
        "customerId", AttributeValue.fromS(customer), "beneficiaryId", AttributeValue.fromS(id));
  }

  static QueryResponse page(
      Map<String, AttributeValue> last, List<Map<String, AttributeValue>> rows) {
    QueryResponse.Builder b = QueryResponse.builder().items(rows);
    if (!last.isEmpty()) {
      b.lastEvaluatedKey(last);
    }
    return b.build();
  }

  @Test
  void findsOnlyActiveBeneficiariesByTheirFullKey() {
    assertThat(repository.findActive("C-1", "B-1")).isEmpty();
    GetItemRequest get = dynamo.last(GetItemRequest.class);
    assertThat(get.tableName()).isEqualTo("local-beneficiaries");
    assertThat(get.key()).isEqualTo(key("C-1", "B-1"));
    assertThat(get.consistentRead()).isTrue();

    dynamo.item = row("C-1", "B-1", false);
    Beneficiary found = repository.findActive("C-1", "B-1").orElseThrow();
    assertThat(found.getAlias()).isEqualTo("Landlord");
    assertThat(found.getCreatedAt()).isEqualTo(NOW);
    assertThat(found.getVersion()).isEqualTo(1L);

    dynamo.item = row("C-1", "B-1", true);
    assertThat(repository.findActive("C-1", "B-1")).isEmpty();
  }

  @Test
  void listsOnePageWithoutRemovedItemsAndAnOpaqueCursor() {
    dynamo.pages.add(page(key("C-1", "B-1"), List.of(row("C-1", "B-1", false))));

    Page<Beneficiary> first = repository.listActive("C-1", 1, null);

    QueryRequest q = dynamo.last(QueryRequest.class);
    assertThat(q.tableName()).isEqualTo("local-beneficiaries");
    assertThat(q.limit()).isEqualTo(1);
    assertThat(q.filterExpression()).isEqualTo("attribute_not_exists(deletedAt)");
    assertThat(q.exclusiveStartKey()).isEmpty();
    assertThat(q.expressionAttributeValues()).containsValue(AttributeValue.fromS("C-1"));
    assertThat(first.content()).extracting(Beneficiary::getBeneficiaryId).containsExactly("B-1");
    assertThat(CursorCodec.decode(first.nextCursor())).isEqualTo(key("C-1", "B-1"));

    dynamo.pages.add(page(Map.of(), List.of(row("C-1", "B-2", false))));
    Page<Beneficiary> second = repository.listActive("C-1", 1, first.nextCursor());

    assertThat(dynamo.last(QueryRequest.class).exclusiveStartKey()).isEqualTo(key("C-1", "B-1"));
    assertThat(second.content()).extracting(Beneficiary::getBeneficiaryId).containsExactly("B-2");
    assertThat(second.nextCursor()).isNull();
  }

  @Test
  void aCursorOfAnotherCustomerOrMalformedIsRefusedBeforeReading() {
    String foreign = CursorCodec.encode(key("C-2", "B-9"));
    String withoutSortKey = CursorCodec.encode(Map.of("customerId", AttributeValue.fromS("C-1")));
    String withoutOwner = CursorCodec.encode(Map.of("beneficiaryId", AttributeValue.fromS("B-1")));

    for (String cursor : new String[] {foreign, withoutSortKey, withoutOwner, "%%not-base64%%"}) {
      assertThatThrownBy(() -> repository.listActive("C-1", 10, cursor))
          .as(cursor)
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.definition()).isEqualTo(BeneficiaryErrors.INVALID_CURSOR));
    }
    assertThat(dynamo.queries()).isEmpty();
  }

  @Test
  void allActiveFollowsEveryPage() {
    dynamo.pages.add(page(key("C-1", "B-1"), List.of(row("C-1", "B-1", false))));
    dynamo.pages.add(page(Map.of(), List.of(row("C-1", "B-2", false))));

    assertThat(repository.allActive("C-1"))
        .extracting(Beneficiary::getBeneficiaryId)
        .containsExactly("B-1", "B-2");
    assertThat(dynamo.queries()).hasSize(2);
    assertThat(dynamo.queries().get(0).filterExpression())
        .isEqualTo("attribute_not_exists(deletedAt)");
    assertThat(dynamo.queries().get(1).exclusiveStartKey()).isEqualTo(key("C-1", "B-1"));
  }

  @Test
  void insertsOnlyNewKeysAndUpdatesWithTheStoredVersion() {
    Beneficiary b =
        Beneficiary.register(
            "C-1",
            "B-1",
            new NewBeneficiary("Landlord", "Ana Torres", "00219100123456789053", "PEN"),
            NOW);

    repository.insert(b);
    PutItemRequest put = dynamo.last(PutItemRequest.class);
    assertThat(put.tableName()).isEqualTo("local-beneficiaries");
    assertThat(put.conditionExpression()).contains("attribute_not_exists(#pk)");
    assertThat(put.expressionAttributeNames()).containsEntry("#pk", "customerId");
    assertThat(put.item()).containsEntry("cci", AttributeValue.fromS("00219100123456789053"));

    dynamo.item = row("C-1", "B-1", false);
    b.setVersion(1L);
    b.rename("Rent", NOW);
    Beneficiary saved = repository.update(b);
    UpdateItemRequest update = dynamo.last(UpdateItemRequest.class);
    assertThat(update.tableName()).isEqualTo("local-beneficiaries");
    assertThat(update.key()).isEqualTo(key("C-1", "B-1"));
    assertThat(update.conditionExpression()).isNotBlank();
    assertThat(saved.getBeneficiaryId()).isEqualTo("B-1");
  }
}

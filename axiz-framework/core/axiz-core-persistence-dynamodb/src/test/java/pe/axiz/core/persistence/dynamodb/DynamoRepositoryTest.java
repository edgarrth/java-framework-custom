package pe.axiz.core.persistence.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags.primaryPartitionKey;
import static software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags.primarySortKey;
import static software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags.secondaryPartitionKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.Page;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticTableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/**
 * The repository over the real Enhanced Client and a scripted low-level client: which requests it
 * sends (keys, consistency, conditions, limits, start keys, indexes) and how it pages.
 */
class DynamoRepositoryTest {

  /** A beneficiary of a customer: partition = customer, sort = beneficiary, index by status. */
  public static final class Beneficiary {
    private String customer;
    private String id;
    private String status;

    public Beneficiary() {}

    Beneficiary(String customer, String id, String status) {
      this.customer = customer;
      this.id = id;
      this.status = status;
    }

    public String getCustomer() {
      return customer;
    }

    public void setCustomer(String customer) {
      this.customer = customer;
    }

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }
  }

  static final TableSchema<Beneficiary> SCHEMA =
      StaticTableSchema.builder(Beneficiary.class)
          .newItemSupplier(Beneficiary::new)
          .addAttribute(
              String.class,
              a ->
                  a.name("customer")
                      .getter(Beneficiary::getCustomer)
                      .setter(Beneficiary::setCustomer)
                      .tags(primaryPartitionKey()))
          .addAttribute(
              String.class,
              a ->
                  a.name("id")
                      .getter(Beneficiary::getId)
                      .setter(Beneficiary::setId)
                      .tags(primarySortKey()))
          .addAttribute(
              String.class,
              a ->
                  a.name("status")
                      .getter(Beneficiary::getStatus)
                      .setter(Beneficiary::setStatus)
                      .tags(secondaryPartitionKey("by-status")))
          .build();

  static Map<String, AttributeValue> row(String customer, String id, String status) {
    return Map.of(
        "customer", AttributeValue.fromS(customer),
        "id", AttributeValue.fromS(id),
        "status", AttributeValue.fromS(status));
  }

  /** Low-level client that records requests and answers from a script. */
  static final class Scripted implements DynamoDbClient {
    final List<Object> requests = new ArrayList<>();
    Map<String, AttributeValue> item = Map.of();
    Map<String, AttributeValue> lastKey = Map.of();

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
    public DeleteItemResponse deleteItem(DeleteItemRequest r) {
      requests.add(r);
      return DeleteItemResponse.builder().build();
    }

    @Override
    public QueryResponse query(QueryRequest r) {
      requests.add(r);
      QueryResponse.Builder b =
          QueryResponse.builder()
              .items(List.of(row("C-1", "B-1", "ACTIVE"), row("C-1", "B-2", "ACTIVE")));
      if (!lastKey.isEmpty()) {
        b.lastEvaluatedKey(lastKey);
      }
      return b.build();
    }

    @Override
    public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest r) {
      requests.add(r);
      return TransactWriteItemsResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "dynamodb";
    }

    @Override
    public void close() {}

    @SuppressWarnings("unchecked")
    <R> R last(Class<R> type) {
      for (int i = requests.size() - 1; i >= 0; i--) {
        if (type.isInstance(requests.get(i))) {
          return (R) requests.get(i);
        }
      }
      throw new AssertionError("no " + type.getSimpleName() + " in " + requests);
    }
  }

  private final Scripted dynamo = new Scripted();
  private final DynamoDbEnhancedClient enhanced =
      DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();
  private final DynamoRepository<Beneficiary> repository =
      new DynamoRepository<>(enhanced, "beneficiaries", SCHEMA);

  @Test
  void readsAreConsistentAndByFullKey() {
    assertTrue(repository.findById("C-1").isEmpty());
    GetItemRequest byPartition = dynamo.last(GetItemRequest.class);
    assertEquals("beneficiaries", byPartition.tableName());
    assertTrue(byPartition.consistentRead());
    assertEquals(Map.of("customer", AttributeValue.fromS("C-1")), byPartition.key());

    dynamo.item = row("C-1", "B-1", "ACTIVE");
    Beneficiary found = repository.findById("C-1", "B-1").orElseThrow();
    assertEquals("B-1", found.getId());
    assertEquals(
        Map.of("customer", AttributeValue.fromS("C-1"), "id", AttributeValue.fromS("B-1")),
        dynamo.last(GetItemRequest.class).key());
    assertEquals("beneficiaries", repository.table().tableName());
  }

  @Test
  void writesAndDeletes() {
    repository.save(new Beneficiary("C-1", "B-1", "ACTIVE"));
    assertNull(dynamo.last(PutItemRequest.class).conditionExpression(), "a plain save overwrites");

    repository.insert(new Beneficiary("C-1", "B-2", "ACTIVE"), "customer");
    PutItemRequest insert = dynamo.last(PutItemRequest.class);
    assertEquals("attribute_not_exists(#pk)", insert.conditionExpression());
    assertEquals(Map.of("#pk", "customer"), insert.expressionAttributeNames());

    dynamo.item = row("C-1", "B-1", "BLOCKED");
    assertEquals(
        "BLOCKED", repository.update(new Beneficiary("C-1", "B-1", "BLOCKED")).getStatus());
    assertEquals("beneficiaries", dynamo.last(UpdateItemRequest.class).tableName());

    repository.delete("C-1", "B-1");
    assertEquals(
        Map.of("customer", AttributeValue.fromS("C-1"), "id", AttributeValue.fromS("B-1")),
        dynamo.last(DeleteItemRequest.class).key());
  }

  @Test
  void aTableWithOnlyAPartitionKeyDeletesByIt() {
    Scripted plain = new Scripted();
    TableSchema<Beneficiary> partitionOnly =
        StaticTableSchema.builder(Beneficiary.class)
            .newItemSupplier(Beneficiary::new)
            .addAttribute(
                String.class,
                a ->
                    a.name("customer")
                        .getter(Beneficiary::getCustomer)
                        .setter(Beneficiary::setCustomer)
                        .tags(primaryPartitionKey()))
            .build();
    DynamoRepository<Beneficiary> customers =
        new DynamoRepository<>(
            DynamoDbEnhancedClient.builder().dynamoDbClient(plain).build(),
            "customers",
            partitionOnly);
    customers.delete("C-9");
    assertEquals(
        Map.of("customer", AttributeValue.fromS("C-9")), plain.last(DeleteItemRequest.class).key());
  }

  @Test
  void queriesPageWithAnOpaqueCursor() {
    dynamo.lastKey =
        Map.of("customer", AttributeValue.fromS("C-1"), "id", AttributeValue.fromS("B-2"));
    Page<Beneficiary> first = repository.query("C-1", 2, null);
    assertEquals(2, first.content().size());
    QueryRequest q = dynamo.last(QueryRequest.class);
    assertEquals(2, q.limit());
    assertEquals("#AMZN_MAPPED_customer = :AMZN_MAPPED_customer", q.keyConditionExpression());
    assertTrue(q.exclusiveStartKey().isEmpty());
    assertTrue(first.nextCursor() != null);

    // the cursor of a page is the start of the next one
    dynamo.lastKey = Map.of();
    Page<Beneficiary> second = repository.query("C-1", 2, first.nextCursor());
    assertEquals(
        Map.of("customer", AttributeValue.fromS("C-1"), "id", AttributeValue.fromS("B-2")),
        dynamo.last(QueryRequest.class).exclusiveStartKey());
    assertNull(second.nextCursor(), "the last page has no cursor");

    repository.queryBeginsWith("C-1", "B-", 10, null);
    assertTrue(dynamo.last(QueryRequest.class).keyConditionExpression().contains("begins_with"));

    repository.queryIndex("by-status", "ACTIVE", 5, null);
    QueryRequest byIndex = dynamo.last(QueryRequest.class);
    assertEquals("by-status", byIndex.indexName());
    assertEquals(
        AttributeValue.fromS("ACTIVE"),
        byIndex.expressionAttributeValues().values().iterator().next());
  }

  @Test
  void limitsAndCursorsAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> repository.query("C-1", 0, null));
    assertThrows(IllegalArgumentException.class, () -> repository.query("C-1", 501, null));
    assertThrows(IllegalArgumentException.class, () -> repository.query("C-1", 10, "not-a-cursor"));
    assertTrue(dynamo.requests.isEmpty(), "nothing is sent for an invalid request");
  }

  @Test
  void transactionsAreBuiltByTheCaller() {
    Beneficiary b = new Beneficiary("C-1", "B-3", "ACTIVE");
    repository.transact(
        t ->
            t.addPutItem(
                repository.table(),
                TransactPutItemEnhancedRequest.builder(Beneficiary.class).item(b).build()));
    TransactWriteItemsRequest tx = dynamo.last(TransactWriteItemsRequest.class);
    assertEquals(1, tx.transactItems().size());
    assertEquals("beneficiaries", tx.transactItems().get(0).put().tableName());
    assertSame(TransactWriteItemsRequest.class, tx.getClass());
  }

  @Test
  void cursorsKeepNumberKeysAndRejectOtherTypes() {
    Map<String, AttributeValue> numeric = Map.of("seq", AttributeValue.fromN("7"));
    assertEquals(numeric, CursorCodec.decode(CursorCodec.encode(numeric)));
    assertNull(CursorCodec.encode(null));
    assertNull(CursorCodec.decode(null));
    String bool =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"k\":{\"BOOL\":true}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> CursorCodec.decode(bool));
    String plain =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"k\":\"v\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> CursorCodec.decode(plain));
  }

  @Test
  void anEncryptedAttributeOfAnotherTypeIsRejected() {
    EncryptedAttributeConverter converter = new EncryptedAttributeConverter();
    assertThrows(
        IllegalArgumentException.class, () -> converter.transformTo(AttributeValue.fromN("1")));
    assertEquals(String.class, converter.type().rawClass());
    assertEquals(
        software.amazon.awssdk.enhanced.dynamodb.AttributeValueType.S,
        converter.attributeValueType());
    MoneyAttributeConverter money = new MoneyAttributeConverter();
    assertEquals(pe.axiz.core.money.Money.class, money.type().rawClass());
    assertEquals(
        software.amazon.awssdk.enhanced.dynamodb.AttributeValueType.S, money.attributeValueType());
    assertEquals(
        Key.builder().partitionValue("x").build().partitionKeyValue(), AttributeValue.fromS("x"));
  }
}

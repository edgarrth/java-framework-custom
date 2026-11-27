package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.sensitive.ObjectMasker;

class AuditSnapshotTest {
  enum Unstable {
    VALUE;

    @Override
    public String toString() {
      throw new AssertionError("use name");
    }
  }

  static AuditEvent event(Map<String, Object> details) {
    return AuditEvent.builder()
        .id("1")
        .occurredAt(Instant.EPOCH)
        .service("service")
        .action("create")
        .resourceType("resource")
        .resourceId(null)
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(details)
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void freezesEveryLevelAndTransportMetadataNeverChangesTheFact() {
    var atomic = new AtomicInteger(7);
    var list = new ArrayList<>(List.of(atomic));
    var source = new LinkedHashMap<String, Object>();
    source.put("items", list);
    var event = event(source);
    source.clear();
    list.clear();
    atomic.set(99);
    assertEquals(List.of(7), event.details().get("items"));
    assertThrows(UnsupportedOperationException.class, () -> event.details().put("x", 1));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((List<Object>) event.details().get("items")).add(8));
    assertThrows(
        UnsupportedOperationException.class,
        () -> event.details().entrySet().iterator().next().setValue(2));
    assertThrows(UnsupportedOperationException.class, () -> event.toMap().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((Map<String, Object>) event.toMap().get("actor")).clear());
    var degraded = event.withPersistence("NOT_CONFIRMED");
    assertSame(event.details(), degraded.details());
    assertEquals(event.detailsSanitized(), degraded.detailsSanitized());
    assertFalse(event.details().containsKey("audit.persistence"));
    assertEquals("NOT_CONFIRMED", degraded.toMap().get("persistence"));
    assertSame(
        event.details(),
        event.withOutcomes(TransactionOutcome.COMMITTED, TransactionOutcome.COMMITTED).details());
  }

  @Test
  void closedScalarTypesCyclesInvalidKeysAndRangesAreAccountedFor() {
    var nested = new LinkedHashMap<Object, Object>();
    nested.put(1, "dropped");
    nested.put("1", "kept");
    var cycle = new ArrayList<Object>();
    cycle.add(cycle);
    Object unknown =
        new Object() {
          @Override
          public String toString() {
            throw new AssertionError("never stringify");
          }
        };
    var source = new LinkedHashMap<String, Object>();
    source.put("keys", nested);
    source.put("x".repeat(129), "dropped");
    source.put("decimal", new BigDecimal("1E+4096"));
    source.put("integer", BigInteger.ONE.shiftLeft(4096));
    source.put("unknown", unknown);
    source.put("enum", Unstable.VALUE);
    source.put("cycle", cycle);
    source.put("null", null);
    var snapshot = event(source);
    assertEquals(6, snapshot.detailsSanitized());
    assertEquals(Map.of("1", "kept"), snapshot.details().get("keys"));
    assertEquals("VALUE", snapshot.details().get("enum"));
    assertEquals(List.of("<cycle>"), snapshot.details().get("cycle"));
    assertTrue(snapshot.details().containsKey("null"));
    assertBounded(snapshot);
  }

  @Test
  void budgetsApplyToTheFinalTreeWithNamesEscapesAndMarkers() {
    var nodes = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 10; i++) nodes.put("list" + i, java.util.Collections.nCopies(50, 1));
    var text = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 16; i++) text.put("text" + i, "\u0001".repeat(2000));
    var keys = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 300; i++)
      keys.put("k".repeat(125) + String.format("%03d", i), new Object());
    for (var details : List.of(nodes, text, keys)) {
      var snapshot = event(details);
      assertTrue(snapshot.detailsSanitized() > 0);
      assertBounded(snapshot);
    }
    Object nested = "end";
    for (int i = 0; i < 100; i++) nested = List.of(nested);
    var snapshot = event(Map.of("nested", nested));
    assertTrue(JsonWriter.writeCanonical(snapshot.details()).contains("<truncated>"));
    assertBounded(snapshot);
  }

  @Test
  void throwingSubtreeReturnsItsBudgetAndCountsAsOneReplacement() {
    var hostile =
        new AbstractCollection<Object>() {
          @Override
          public int size() {
            return 10;
          }

          @Override
          public Iterator<Object> iterator() {
            return new Iterator<>() {
              int position;

              @Override
              public boolean hasNext() {
                return true;
              }

              @Override
              public Object next() {
                if (position++ == 2) throw new IllegalStateException("sensitive");
                return "\u0001".repeat(2000);
              }
            };
          }
        };
    var details = new LinkedHashMap<String, Object>();
    details.put("bad", hostile);
    details.put("next", "\u0001".repeat(2000));
    var snapshot = event(details);
    assertEquals(1, snapshot.detailsSanitized());
    assertEquals("<unsupported>", snapshot.details().get("bad"));
    assertEquals(details.get("next"), snapshot.details().get("next"));
    assertBounded(snapshot);
  }

  @Test
  void completeRecordRouteCountsMaskerCollisionsAndPublishersSeeTheSameSnapshot() {
    var record =
        new AuditRecord(
            "service",
            ObjectMasker.defaults(),
            IdGenerator.uuidV7(),
            Clock.systemUTC(),
            "create",
            "resource");
    var values = new LinkedHashMap<Object, Object>();
    values.put(1, "first");
    values.put("1", "second");
    record.detail("data", values);
    var event = record.event(AuditEvent.Outcome.SUCCESS, null);
    assertEquals(1, event.detailsSanitized());
    var observed = new ArrayList<String>();
    new CompositeAuditPublisher(
            List.of(
                e -> {
                  assertThrows(UnsupportedOperationException.class, () -> e.details().clear());
                  observed.add(JsonWriter.writeCanonical(e.toMap()));
                },
                e -> observed.add(JsonWriter.writeCanonical(e.toMap()))))
        .publish(event);
    assertEquals(observed.get(0), observed.get(1));
  }

  private static void assertBounded(AuditEvent snapshot) {
    String json = JsonWriter.writeCanonical(snapshot.details());
    assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 32 * 1024);
    assertTrue(nodes(snapshot.details()) <= 500);
    assertNotNull(JsonReader.parseObject(json));
  }

  private static int nodes(Object value) {
    if (value instanceof Map<?, ?> map)
      return 1 + map.values().stream().mapToInt(AuditSnapshotTest::nodes).sum();
    if (value instanceof Collection<?> list)
      return 1 + list.stream().mapToInt(AuditSnapshotTest::nodes).sum();
    return 1;
  }
}

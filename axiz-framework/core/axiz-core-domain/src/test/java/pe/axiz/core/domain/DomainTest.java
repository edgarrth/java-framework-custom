package pe.axiz.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.id.UuidV7;

class DomainTest {

  record WidgetId(UUID value) implements TypedId {}

  record WidgetRenamedEvent(String eventId, Instant occurredAt, String aggregateId, String name)
      implements DomainEvent {}

  static final class Widget extends AggregateRoot<WidgetId> {
    private final WidgetId id;
    private String name;

    Widget(WidgetId id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    protected WidgetId id() {
      return id;
    }

    void rename(String newName) {
      Rules.check(!newName.isBlank(), DomainErrors.RULE_VIOLATED, "blank name");
      this.name = newName;
      registerEvent(
          new WidgetRenamedEvent(UuidV7.generate().toString(), Instant.EPOCH, id.asString(), name));
    }
  }

  @Test
  void aggregateRecordsAndPullsEvents() {
    Widget w = new Widget(new WidgetId(TypedId.newUuid()), "a");
    w.rename("b");
    assertEquals(1, w.peekEvents().size());
    var events = w.pullEvents();
    assertEquals(1, events.size());
    assertEquals("widget-renamed", events.get(0).eventType());
    assertTrue(w.pullEvents().isEmpty());
    assertThrows(BusinessException.class, () -> w.rename(" "));
  }

  @Test
  void entitiesCompareById() {
    WidgetId id = new WidgetId(TypedId.newUuid());
    assertEquals(new Widget(id, "a"), new Widget(id, "b"));
    assertEquals(new Widget(id, "a").hashCode(), new Widget(id, "b").hashCode());
    assertNotEquals(new Widget(id, "a"), new Widget(new WidgetId(TypedId.newUuid()), "a"));
    assertTrue(UuidV7.isV7(id.value()));
  }

  @Test
  void specificationsCompose() {
    Specification<Integer> positive = v -> v > 0;
    Specification<Integer> even = v -> v % 2 == 0;
    assertTrue(positive.and(even).isSatisfiedBy(4));
    assertFalse(positive.and(even).isSatisfiedBy(3));
    assertTrue(positive.or(even).isSatisfiedBy(-2));
    assertTrue(positive.not().isSatisfiedBy(-1));
  }

  @Test
  void commonValueObjects() {
    assertEquals("ana@example.com", new EmailAddress(" Ana@Example.com ").value());
    assertEquals("a**@example.com", new EmailAddress("ana@example.com").toString());
    assertThrows(BusinessException.class, () -> new EmailAddress("nope"));
    assertEquals("+51987654321", new PhoneNumber("+51 987-654-321").value());
    assertThrows(BusinessException.class, () -> new PhoneNumber("987"));
    PersonalDocument dni = new PersonalDocument(PersonalDocument.Type.parse("dni"), "12345678");
    assertEquals("DNI:****5678", dni.toString());
    assertThrows(
        BusinessException.class,
        () -> new PersonalDocument(PersonalDocument.Type.RUC, "20100070971"));
    assertThrows(BusinessException.class, () -> new PersonalDocument(null, "1"));
  }

  @Test
  void eventTypeNaming() {
    assertEquals("payment-authorized", EventTypes.kebab("PaymentAuthorizedEvent"));
    assertEquals("event", EventTypes.kebab("Event"));
    assertEquals("kyc2-passed", EventTypes.kebab("Kyc2Passed"));
  }

  static final class Gadget extends AggregateRoot<WidgetId> {
    private final WidgetId id;

    Gadget(WidgetId id) {
      this.id = id;
    }

    @Override
    protected WidgetId id() {
      return id;
    }
  }

  @Test
  void entitiesOfAnotherTypeOrNothingAreNotEqual() {
    WidgetId id = new WidgetId(TypedId.newUuid());
    Widget w = new Widget(id, "a");
    assertEquals(w, w);
    assertNotEquals(w, new Gadget(id), "same id, other type");
    assertNotEquals(w, null);
  }

  @Test
  void specificationsShortCircuitLikeBooleans() {
    Specification<Integer> positive = v -> v > 0;
    Specification<Integer> never =
        v -> {
          throw new AssertionError("must not be evaluated");
        };
    assertFalse(positive.and(never).isSatisfiedBy(-1));
    assertTrue(positive.or(never).isSatisfiedBy(1));
    assertFalse(positive.or(v -> v % 2 == 0).isSatisfiedBy(-3));
    assertFalse(positive.not().isSatisfiedBy(1));
  }

  @Test
  void rulesCarryTheirParameters() {
    Rules.check(true, DomainErrors.RULE_VIOLATED, "fine", Map.of("limit", 10));
    BusinessException e =
        assertThrows(
            BusinessException.class,
            () ->
                Rules.check(false, DomainErrors.RULE_VIOLATED, "over limit", Map.of("limit", 10)));
    assertEquals(Map.of("limit", 10), e.parameters());
  }

  @Test
  void missingValuesAreRejectedAndPhonesAreMasked() {
    assertThrows(BusinessException.class, () -> new EmailAddress(null));
    assertThrows(BusinessException.class, () -> new PhoneNumber(null));
    assertThrows(
        BusinessException.class, () -> new PersonalDocument(PersonalDocument.Type.DNI, null));
    String masked = new PhoneNumber("+51 987-654-321").toString();
    assertFalse(masked.contains("987654"), masked);
    assertTrue(masked.endsWith("321"), masked);
  }
}

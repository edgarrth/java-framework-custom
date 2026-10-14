package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.project.ProjectManifest;

class ArtifactGeneratorTest {

  private final ArtifactGenerator generator =
      new ArtifactGenerator(Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));

  private static ProjectManifest project(
      Framework f, Stack s, String persistence, List<String> caps) {
    return new ProjectManifest(
        "orders-business",
        "pe.axiz.orders",
        "pe.axiz.orders.business",
        "business",
        f,
        s,
        persistence,
        "1.0.0",
        "src/main/openapi/openapi.yaml",
        null,
        caps,
        List.of());
  }

  private static String text(ArtifactGenerator.Result r, String suffix) {
    return r.created().entrySet().stream()
        .filter(e -> e.getKey().endsWith(suffix))
        .findFirst()
        .map(e -> new String(e.getValue(), StandardCharsets.UTF_8))
        .orElseThrow(() -> new AssertionError(suffix + " not in " + r.created().keySet()));
  }

  @Test
  void everyTypeRendersForEveryStyleItAppliesTo() {
    for (Framework f :
        List.of(
            Framework.HEXAGONAL,
            Framework.CQRS,
            Framework.LAYERED,
            Framework.BFF,
            Framework.EDA,
            Framework.BATCH)) {
      for (String type : ArtifactGenerator.typesFor(f)) {
        String persistence =
            type.equals("entity") || type.equals("repository") || type.equals("migration")
                ? "aurora"
                : "none";
        ArtifactGenerator.Result r =
            generator.generate(
                project(f, Stack.SERVLET, persistence, List.of()),
                type,
                "payment-order",
                ArtifactGenerator.Options.none(),
                null);
        assertFalse(r.created().isEmpty(), f + " " + type);
        r.created()
            .forEach(
                (path, bytes) ->
                    assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("{{"), path));
      }
    }
  }

  @Test
  void followsTheStyleLayout() {
    ArtifactGenerator.Result useCase =
        generator.generate(
            project(Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of()),
            "usecase",
            "approve-payment",
            ArtifactGenerator.Options.none(),
            null);
    assertTrue(
        useCase
            .created()
            .containsKey(
                "src/main/java/pe/axiz/orders/business/application/usecase/ApprovePaymentUseCase.java"));
    assertTrue(
        useCase
            .created()
            .containsKey(
                "src/test/java/pe/axiz/orders/business/application/usecase/ApprovePaymentUseCaseTest.java"));

    ArtifactGenerator.Result entity =
        generator.generate(
            project(Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of()),
            "entity",
            "payment",
            ArtifactGenerator.Options.none(),
            null);
    assertTrue(
        text(entity, "adapter/out/persistence/PaymentEntity.java")
            .contains("extends AuditableEntity"));
    assertTrue(
        entity
            .created()
            .containsKey("src/main/resources/db/migration/V20261001100000__create_payment.sql"));

    ArtifactGenerator.Result item =
        generator.generate(
            project(Framework.LAYERED, Stack.SERVLET, "dynamodb", List.of()),
            "repository",
            "payment",
            ArtifactGenerator.Options.none(),
            null);
    assertTrue(
        text(item, "repository/PaymentRepository.java")
            .contains("repositories.create(\"payments\", PaymentItem.class)"));

    ArtifactGenerator.Result handler =
        generator.generate(
            project(Framework.EDA, Stack.REACTIVE, "none", List.of("messaging-kafka")),
            "event-handler",
            "payment-approved",
            ArtifactGenerator.Options.none(),
            null);
    assertTrue(
        text(handler, "handler/PaymentApprovedHandler.java")
            .contains("@HandlesEvent(\"pe.axiz.orders.payment.approved.v1\")"));
    assertTrue(handler.notes().stream().noneMatch(n -> n.contains("axiz add messaging")));

    ArtifactGenerator.Result hexHandler =
        generator.generate(
            project(Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of()),
            "handler",
            "payment-approved",
            ArtifactGenerator.Options.none(),
            null);
    assertTrue(
        text(hexHandler, "adapter/in/messaging/PaymentApprovedHandler.java")
            .contains("implements EventHandler<PaymentApproved>"));
    assertTrue(hexHandler.notes().stream().anyMatch(n -> n.contains("axiz add messaging-kafka")));

    ArtifactGenerator.Result mapper =
        generator.generate(
            project(Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of()),
            "mapper",
            "payment",
            new ArtifactGenerator.Options(null, null, "com.acme.api.PaymentDto", "Payment"),
            null);
    assertTrue(
        text(mapper, "PaymentMapper.java")
            .contains("Payment toPayment(com.acme.api.PaymentDto source);"));
    assertEquals(List.of("org.mapstruct:mapstruct"), mapper.dependencies());
  }

  @Test
  void rejectsWhatDoesNotApply() {
    ProjectManifest hex = project(Framework.HEXAGONAL, Stack.SERVLET, "none", List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate(hex, "command", "x", ArtifactGenerator.Options.none(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate(hex, "entity", "x", ArtifactGenerator.Options.none(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate(hex, "migration", "x", ArtifactGenerator.Options.none(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate(hex, "spaceship", "x", ArtifactGenerator.Options.none(), null));
    ProjectManifest eda = project(Framework.EDA, Stack.SERVLET, "none", List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate(eda, "controller", "x", ArtifactGenerator.Options.none(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            generator.generate(
                hex,
                "error-code",
                "x",
                new ArtifactGenerator.Options("SOMETIMES", null, null, null),
                null));
  }

  @Test
  void errorCodesAreNumberedAndAppended() {
    ProjectManifest p = project(Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of());
    ArtifactGenerator.Result first =
        generator.generate(
            p,
            "error-code",
            "limit-exceeded",
            new ArtifactGenerator.Options("unprocessable", "Daily limit exceeded", null, null),
            null);
    String path = "src/main/java/pe/axiz/orders/business/domain/OrdersBusinessErrors.java";
    String errors = new String(first.created().get(path), StandardCharsets.UTF_8);
    assertTrue(
        errors.contains(
            "\"ORDERS-ORDERSBUSINESS-0001\", ErrorCategory.UNPROCESSABLE, \"Daily limit"
                + " exceeded\""),
        errors);
    assertEquals(
        "error.ORDERS-ORDERSBUSINESS-0001=Daily limit exceeded\n",
        first.appended().get("src/main/resources/messages.properties"));

    ArtifactGenerator.Result second =
        generator.generate(
            p, "error-code", "account-blocked", ArtifactGenerator.Options.none(), errors);
    String updated = new String(second.updated().get(path), StandardCharsets.UTF_8);
    assertTrue(updated.contains("LIMIT_EXCEEDED") && updated.contains("ACCOUNT_BLOCKED"));
    assertTrue(updated.contains("ORDERS-ORDERSBUSINESS-0002"));
    assertTrue(second.created().isEmpty());
  }
}

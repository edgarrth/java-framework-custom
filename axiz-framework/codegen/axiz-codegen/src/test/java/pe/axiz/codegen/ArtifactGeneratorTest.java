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
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.project.ProjectManifest;

class ArtifactGeneratorTest {

  private final ArtifactGenerator generator =
      new ArtifactGenerator(Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));

  private static ProjectManifest project(
      Structure f, Stack s, String persistence, List<String> caps) {
    return new ProjectManifest(
        "orders",
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
    for (Structure f :
        List.of(
            Structure.HEXAGONAL,
            Structure.HEXAGONAL,
            Structure.LAYERED,
            Structure.BFF,
            Structure.EDA,
            Structure.BATCH)) {
      for (String type : ArtifactGenerator.typesFor(f)) {
        String persistence =
            type.equals("entity") || type.equals("repository") || type.equals("migration")
                ? "aurora"
                : "none";
        ArtifactGenerator.Result r =
            generator.generate(
                project(f, Stack.SERVLET, persistence, List.of("messaging-kafka")),
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
            project(Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of()),
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
            project(Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of()),
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
            project(Structure.LAYERED, Stack.SERVLET, "dynamodb", List.of()),
            "repository",
            "payment",
            ArtifactGenerator.Options.none(),
            null);
    assertTrue(
        text(item, "repository/PaymentRepository.java")
            .contains("repositories.create(\"payments\", PaymentItem.class)"));

    ArtifactGenerator.Result reactiveDynamo =
        generator.generate(
            project(Structure.HEXAGONAL, Stack.REACTIVE, "dynamodb", List.of()),
            "repository",
            "payment",
            ArtifactGenerator.Options.none(),
            null);
    String reactiveRepository =
        text(reactiveDynamo, "adapter/out/persistence/PaymentRepository.java");
    assertTrue(reactiveRepository.contains("ReactiveDynamoRepositories repositories"));
    assertTrue(reactiveRepository.contains("Mono<PaymentItem> findById"));
    assertTrue(reactiveRepository.contains("return items.save(item);"));
    assertFalse(reactiveRepository.contains("java.util.Optional"));
    assertFalse(reactiveRepository.contains("boundedElastic"));

    ArtifactGenerator.Result handler =
        generator.generate(
            project(Structure.EDA, Stack.REACTIVE, "none", List.of("messaging-kafka")),
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
            project(Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of("messaging-sqs")),
            "handler",
            "payment-approved",
            ArtifactGenerator.Options.none(),
            null);
    assertTrue(
        text(hexHandler, "adapter/in/messaging/PaymentApprovedHandler.java")
            .contains("implements EventHandler<PaymentApproved>"));
    // without a messaging capability the handler would not compile: generation is refused
    ProjectManifest noMessaging = project(Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of());
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                generator.generate(
                    noMessaging, "handler", "x", ArtifactGenerator.Options.none(), null));
    assertTrue(refused.getMessage().contains("axiz add messaging-kafka"));

    // reactive services on Aurora get Spring Data R2DBC types, never JPA
    ArtifactGenerator.Result reactiveEntity =
        generator.generate(
            project(Structure.EDA, Stack.REACTIVE, "aurora", List.of("messaging-kafka")),
            "entity",
            "payment",
            ArtifactGenerator.Options.none(),
            null);
    String row =
        reactiveEntity.created().entrySet().stream()
            .filter(e -> e.getKey().endsWith("PaymentEntity.java"))
            .map(e -> new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    assertTrue(row.contains("extends AuditableRow"));
    assertTrue(!row.contains("jakarta.persistence"));

    ArtifactGenerator.Result mapper =
        generator.generate(
            project(Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of()),
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
    ProjectManifest hex = project(Structure.HEXAGONAL, Stack.SERVLET, "none", List.of());
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
    ProjectManifest eda = project(Structure.EDA, Stack.SERVLET, "none", List.of());
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
    ProjectManifest p = project(Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of());
    ArtifactGenerator.Result first =
        generator.generate(
            p,
            "error-code",
            "limit-exceeded",
            new ArtifactGenerator.Options("unprocessable", "Daily limit exceeded", null, null),
            null);
    String path = "src/main/java/pe/axiz/orders/business/domain/OrdersErrors.java";
    String errors = new String(first.created().get(path), StandardCharsets.UTF_8);
    assertTrue(
        errors.contains(
            "\"ORDERS-ORDERS-0001\", ErrorCategory.UNPROCESSABLE, \"Daily limit" + " exceeded\""),
        errors);
    assertEquals(
        "error.ORDERS-ORDERS-0001=Daily limit exceeded\n",
        first.appended().get("src/main/resources/messages.properties"));

    ArtifactGenerator.Result second =
        generator.generate(
            p, "error-code", "account-blocked", ArtifactGenerator.Options.none(), errors);
    String updated = new String(second.updated().get(path), StandardCharsets.UTF_8);
    assertTrue(updated.contains("LIMIT_EXCEEDED") && updated.contains("ACCOUNT_BLOCKED"));
    assertTrue(updated.contains("ORDERS-ORDERS-0002"));
    assertTrue(second.created().isEmpty());
  }
}

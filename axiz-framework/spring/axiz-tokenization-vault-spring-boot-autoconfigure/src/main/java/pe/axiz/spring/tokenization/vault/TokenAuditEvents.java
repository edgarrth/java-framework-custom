package pe.axiz.spring.tokenization.vault;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEvent.Actor;
import pe.axiz.core.audit.AuditEvent.Outcome;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.tokenization.vault.TokenAuditEntry;

/** One allowlisted mapping for both stacks; sealing belongs to the confirmed audit publisher. */
final class TokenAuditEvents {
  private final String service;
  private final Supplier<UUID> ids;

  TokenAuditEvents(String service, Supplier<UUID> ids) {
    this.service = AxizTokenizationVaultProperties.identifier(service, 128);
    this.ids = Objects.requireNonNull(ids);
  }

  AuditEvent map(TokenAuditEntry entry) {
    Map<String, @Nullable Object> details = new LinkedHashMap<>();
    details.put("operationId", entry.operationId().toString());
    details.put("type", entry.type().value());
    details.put("domain", entry.domain().value());
    details.put("result", entry.result().name());
    details.put("purpose", entry.purpose());
    details.put("tokenReference", entry.tokenReference());
    Outcome result =
        switch (entry.result()) {
          case CREATED, FOUND, ABSENT, REVEALED, DELETED -> Outcome.SUCCESS;
          default -> Outcome.FAILURE;
        };
    String error =
        switch (entry.result()) {
          case DENIED -> "AXZ-TOKEN-0403";
          case INVALID -> "AXZ-TOKEN-0400";
          case RATE_LIMITED -> "AXZ-TOKEN-0429";
          case UNCERTAIN -> "AXZ-TOKEN-0504";
          case CANCELLED, UNAVAILABLE, INTEGRITY_FAILURE -> "AXZ-TOKEN-0503";
          default -> null;
        };
    TransactionOutcome operation =
        switch (entry.result()) {
          case CREATED, DELETED -> TransactionOutcome.COMMITTED;
          default -> TransactionOutcome.UNKNOWN;
        };
    return AuditEvent.builder()
        .id(Objects.requireNonNull(ids.get()).toString())
        .occurredAt(entry.at())
        .service(service)
        .action("token." + entry.operation().name().toLowerCase(Locale.ROOT))
        .resourceType("token-vault")
        .resourceId(entry.tokenReference())
        .actor(new Actor(entry.caller().subject(), entry.caller().clientId(), null))
        .outcome(result)
        .errorCode(error)
        .correlationId(entry.operationId().toString())
        .channel("token-vault")
        .tenant(entry.caller().tenant())
        .details(details)
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(operation)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }
}

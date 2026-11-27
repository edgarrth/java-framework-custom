package pe.axiz.spring.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.TokenAuditEntry;
import pe.axiz.core.tokenization.vault.TokenCaller;

class StrictTokenizationAuditTest {
  private static final Token TOKEN = Token.fromEntropy(new byte[16]);
  private static final TokenDomain DOMAIN = new TokenDomain("billing");

  private static TokenAuditEntry entry(TokenAuditEntry.Result result) {
    return TokenAuditEntry.of(
        TokenOperation.TOKENIZE,
        new TokenCaller(
            "alice", "client-a", "tenant-a", Set.of("SCOPE_tokens:tokenize"), Set.of(DOMAIN)),
        new TokenType("DNI"),
        DOMAIN,
        new UUID(1, 2),
        null,
        result,
        TOKEN,
        Instant.parse("2026-11-17T00:00:00Z"));
  }

  @Test
  void allowlistedEvidenceHasUniqueFactIdentityAndDoesNotInventRollback() {
    List<AuditEvent> published = new ArrayList<>();
    AtomicInteger sequence = new AtomicInteger();
    var audit =
        new StrictTokenizationAudit(
            published::add, "vault", () -> new UUID(0, sequence.incrementAndGet()));
    audit.publishConfirmed(entry(TokenAuditEntry.Result.CREATED));
    audit.publishConfirmed(entry(TokenAuditEntry.Result.UNCERTAIN));
    assertNotEquals(published.get(0).id(), published.get(1).id());
    assertEquals(published.get(0).correlationId(), published.get(1).correlationId());
    assertEquals(TransactionOutcome.COMMITTED, published.get(0).operationOutcome());
    assertEquals(TransactionOutcome.UNKNOWN, published.get(1).operationOutcome());
    assertEquals("AXZ-TOKEN-0504", published.get(1).errorCode());
    assertEquals(TOKEN.masked(), published.get(0).resourceId());
    assertFalse(published.get(0).toMap().toString().contains(TOKEN.reveal()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> published.get(0).details().put("value", "synthetic"));
    assertThrows(
        IllegalStateException.class,
        () ->
            new StrictTokenizationAudit(
                    event -> {
                      throw new IllegalStateException("durable audit unavailable");
                    },
                    "vault",
                    () -> new UUID(1, 1))
                .publishConfirmed(entry(TokenAuditEntry.Result.CREATED)));
  }

  @Test
  void nativeAuditCompletesOnlyAfterAcknowledgementAndPropagatesFailure() {
    var ack = new CompletableFuture<Void>();
    var audit = new AsyncStrictTokenizationAudit(event -> ack, "vault", () -> new UUID(1, 1));
    var result =
        audit.publishConfirmed(entry(TokenAuditEntry.Result.REVEALED)).toCompletableFuture();
    assertFalse(result.isDone());
    ack.completeExceptionally(new IllegalStateException("unavailable"));
    assertThrows(CompletionException.class, result::join);
    var success = new CompletableFuture<Void>();
    var confirmed =
        new AsyncStrictTokenizationAudit(event -> success, "vault", () -> new UUID(2, 2))
            .publishConfirmed(entry(TokenAuditEntry.Result.REVEALED))
            .toCompletableFuture();
    assertFalse(confirmed.isDone());
    success.complete(null);
    confirmed.join();
  }
}

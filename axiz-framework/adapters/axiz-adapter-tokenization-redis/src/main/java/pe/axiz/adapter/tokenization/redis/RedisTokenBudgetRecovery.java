package pe.axiz.adapter.tokenization.redis;

import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import pe.axiz.core.crypto.Hashing;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.vault.VaultScope;

/**
 * Blocking maintenance capability on a separate connection, never a public token API. Inventory
 * detects old reservations; only a trusted termination verifier permits conditional recovery. No
 * age, disconnected client, missing pod or expired request is itself termination evidence.
 */
public final class RedisTokenBudgetRecovery {
  static final String RECONCILE =
      """
      local missing = 0
      for i = 1, 3 do
        local value = redis.call('HGET', KEYS[i], ARGV[1])
        if not value then missing = missing + 1
        elseif value ~= ARGV[2] then return -1 end
      end
      if missing == 3 then return 0 end
      if missing ~= 0 then return -1 end
      for i = 1, 3 do redis.call('HDEL', KEYS[i], ARGV[1]) end
      return 3
      """;
  private final RedisCommands<String, String> redis;
  private final String namespace;
  private final TerminationVerifier verifier;
  private final RecoveryJournal journal;

  /**
   * Must prove this exact execution is permanently fenced AND all accepted provider/Redis I/O has
   * completed or been reconciled, including late effects. Evidence must be durable and bound to the
   * namespace, scope key, lease, process incarnation and operation in the reservation. An
   * unrecognized or unavailable evidence source must return false or throw, never allow.
   */
  @FunctionalInterface
  public interface TerminationVerifier {
    boolean terminated(Reservation reservation, UUID evidence);
  }

  /**
   * Durable administrative audit, independent of business token audit; record intent before I/O.
   */
  @FunctionalInterface
  public interface RecoveryJournal {
    void record(Stage stage, Reservation reservation, UUID evidence);
  }

  /** Unknown outcomes require another conditional reconciliation, never blind deletion. */
  public enum Stage {
    REQUESTED,
    RELEASED,
    ALREADY_RELEASED,
    CONFLICT,
    UNKNOWN
  }

  /** Opaque scope identity used by the administrative evidence authority. */
  public record ScopeIdentity(String namespace, String key) {
    public ScopeIdentity {
      Objects.requireNonNull(namespace);
      Objects.requireNonNull(key);
    }
  }

  /** Immutable evidence target; encoded identity is retained for exact compare-and-delete. */
  public record Reservation(
      ScopeIdentity scope,
      UUID lease,
      TokenBudgetOwner owner,
      UUID operationId,
      TokenOperation operation,
      Instant acquiredAt,
      String encoded) {
    public Reservation {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(lease);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(acquiredAt);
      Objects.requireNonNull(encoded);
    }

    /** Age is for alerting only, never authorization to reclaim capacity. */
    public Duration age(Instant observedAt) {
      Duration age = Duration.between(acquiredAt, observedAt);
      return age.isNegative() ? Duration.ZERO : age;
    }
  }

  /**
   * Scan is not a consistent snapshot; repeat a full cycle and deduplicate leases for monitoring.
   */
  public record Inventory(
      String nextCursor,
      long total,
      Instant observedAt,
      List<Reservation> reservations,
      List<UUID> legacyReservations,
      List<String> invalidEntryDigests) {
    public Inventory {
      reservations = List.copyOf(reservations);
      legacyReservations = List.copyOf(legacyReservations);
      invalidEntryDigests = List.copyOf(invalidEntryDigests);
    }
  }

  public RedisTokenBudgetRecovery(
      StatefulRedisConnection<String, String> connection,
      String namespace,
      TerminationVerifier verifier,
      RecoveryJournal journal) {
    RedisBudgetProtocol.validateConnection(connection);
    if (namespace == null || !namespace.matches("[a-z][a-z0-9.-]{0,63}"))
      throw new IllegalArgumentException("Invalid token budget namespace");
    this.redis = connection.sync();
    this.namespace = namespace;
    this.verifier = Objects.requireNonNull(verifier);
    this.journal = Objects.requireNonNull(journal);
  }

  /** One bounded scan call per configured tenant/type/domain; Redis COUNT is a server hint. */
  public Inventory inspect(VaultScope scope, String cursor) {
    if (cursor == null || !cursor.matches("[0-9]{1,20}"))
      throw new IllegalArgumentException("Invalid inventory cursor");
    String prefix = BudgetKeys.prefix(namespace, scope);
    String key = prefix + "tenant:leases";
    var page = redis.hscan(key, ScanCursor.of(cursor), ScanArgs.Builder.limit(100));
    if (page.getMap().size() > 4096)
      throw new IllegalStateException("Reservation scan exceeds bounded response");
    List<Reservation> reservations = new ArrayList<>();
    List<UUID> legacy = new ArrayList<>();
    List<String> invalid = new ArrayList<>();
    page.getMap()
        .forEach(
            (lease, value) -> {
              try {
                UUID id = UUID.fromString(lease);
                if (value.matches("[0-9]{1,20}\\.[0-9]{1,6}")) legacy.add(id);
                else reservations.add(parse(prefix, id, value));
              } catch (IllegalArgumentException
                  | IllegalStateException
                  | DateTimeException malformed) {
                // Keep scanning; malformed entries are alert-only, never deletion candidates.
                invalid.add(Hashing.sha256Hex(lease));
              }
            });
    List<String> now = redis.time();
    Instant observed =
        Instant.ofEpochSecond(Long.parseLong(now.get(0)), Long.parseLong(now.get(1)) * 1000);
    return new Inventory(
        page.getCursor(), redis.hlen(key), observed, reservations, legacy, invalid);
  }

  /**
   * Verifies external proof, journals intent, then atomically compares all three stored values. A
   * changed, partial or legacy reservation is never removed. Repeating a confirmed removal is
   * idempotent. An unavailable proof/journal/Redis retains uncertainty instead of inventing
   * success.
   */
  public Stage reconcile(VaultScope scope, Reservation reservation, UUID evidence) {
    Objects.requireNonNull(evidence);
    String prefix = BudgetKeys.prefix(namespace, scope);
    // Reparse the encoded value: callers cannot manufacture a different proof target from it.
    Reservation actual = parse(prefix, reservation.lease(), reservation.encoded());
    if (!actual.equals(reservation) || !verifier.terminated(actual, evidence))
      throw new IllegalStateException("Verified termination evidence is required");
    journal.record(Stage.REQUESTED, actual, evidence);
    String[] fields = actual.encoded().split("\\|", -1);
    String[] keys = {fields[5], fields[6], prefix + "tenant:leases"};
    Long removed;
    try {
      removed =
          redis.eval(
              RECONCILE,
              ScriptOutputType.INTEGER,
              keys,
              actual.lease().toString(),
              actual.encoded());
    } catch (RuntimeException uncertain) {
      journal.record(Stage.UNKNOWN, actual, evidence);
      throw new IllegalStateException("Reservation recovery outcome requires reconciliation");
    }
    Stage outcome =
        removed != null && removed == 3
            ? Stage.RELEASED
            : removed != null && removed == 0 ? Stage.ALREADY_RELEASED : Stage.CONFLICT;
    journal.record(outcome, actual, evidence);
    return outcome;
  }

  private Reservation parse(String prefix, UUID lease, String value) {
    if (value.length() > 1024) throw new IllegalStateException("Invalid reservation metadata");
    String[] fields = value.split("\\|", -1);
    if (fields.length != 9
        || !fields[0].equals("v2")
        || !fields[5].matches(Pattern.quote(prefix) + "subject:[0-9a-f]{64}:leases")
        || !fields[6].matches(Pattern.quote(prefix) + "client:[0-9a-f]{64}:leases"))
      throw new IllegalStateException("Invalid reservation metadata");
    long micros = Long.parseLong(fields[8]);
    if (micros < 0 || micros > 999999)
      throw new IllegalStateException("Invalid reservation timestamp");
    return new Reservation(
        new ScopeIdentity(namespace, prefix),
        lease,
        new TokenBudgetOwner(fields[1], UUID.fromString(fields[2])),
        UUID.fromString(fields[3]),
        TokenOperation.valueOf(fields[4]),
        Instant.ofEpochSecond(Long.parseLong(fields[7]), micros * 1000),
        value);
  }
}

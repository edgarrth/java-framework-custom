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
 * Capacidad de mantenimiento bloqueante sobre una conexión separada, nunca una API pública de
 * tokens. El inventario detecta reservas antiguas; solo un verificador de terminación confiable
 * permite la recuperación condicional. Ni la antigüedad, ni un cliente desconectado, ni un pod
 * ausente ni una petición expirada son por sí mismos evidencia de terminación.
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
   * Debe probar que esta ejecución exacta tiene fencing permanente Y que toda la E/S aceptada del
   * proveedor o de Redis terminó o se reconcilió, incluidos los efectos tardíos. La evidencia debe
   * ser durable y estar ligada al namespace, la clave de alcance, el lease, la encarnación del
   * proceso y la operación de la reserva. Una fuente de evidencia no reconocida o no disponible
   * debe devolver false o lanzar una excepción, nunca permitir.
   */
  @FunctionalInterface
  public interface TerminationVerifier {
    boolean terminated(Reservation reservation, UUID evidence);
  }

  /**
   * Auditoría administrativa durable, independiente de la auditoría de tokens de negocio; se
   * registra la intención antes de la E/S.
   */
  @FunctionalInterface
  public interface RecoveryJournal {
    void record(Stage stage, Reservation reservation, UUID evidence);
  }

  /**
   * Los resultados desconocidos requieren otra reconciliación condicional, nunca un borrado a
   * ciegas.
   */
  public enum Stage {
    REQUESTED,
    RELEASED,
    ALREADY_RELEASED,
    CONFLICT,
    UNKNOWN
  }

  /** Identidad opaca de alcance usada por la autoridad de evidencia administrativa. */
  public record ScopeIdentity(String namespace, String key) {
    public ScopeIdentity {
      Objects.requireNonNull(namespace);
      Objects.requireNonNull(key);
    }
  }

  /**
   * Objetivo de evidencia inmutable; la identidad codificada se conserva para un compare-and-delete
   * exacto.
   */
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

    /** La antigüedad sirve solo para alertas, nunca como autorización para recuperar capacidad. */
    public Duration age(Instant observedAt) {
      Duration age = Duration.between(acquiredAt, observedAt);
      return age.isNegative() ? Duration.ZERO : age;
    }
  }

  /**
   * El scan no es una instantánea consistente; se repite un ciclo completo y se deduplican los
   * leases para el monitoreo.
   */
  public record Inventory(
      String nextCursor,
      long total,
      Instant observedAt,
      List<Reservation> reservations,
      List<String> invalidEntryDigests) {
    public Inventory {
      reservations = List.copyOf(reservations);
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

  /**
   * Una llamada de scan acotada por cada tenant/tipo/dominio configurado; COUNT de Redis es una
   * sugerencia para el servidor.
   */
  public Inventory inspect(VaultScope scope, String cursor) {
    if (cursor == null || !cursor.matches("[0-9]{1,20}"))
      throw new IllegalArgumentException("Invalid inventory cursor");
    String prefix = BudgetKeys.prefix(namespace, scope);
    String key = prefix + "tenant:leases";
    var page = redis.hscan(key, ScanCursor.of(cursor), ScanArgs.Builder.limit(100));
    if (page.getMap().size() > 4096)
      throw new IllegalStateException("Reservation scan exceeds bounded response");
    List<Reservation> reservations = new ArrayList<>();
    List<String> invalid = new ArrayList<>();
    page.getMap()
        .forEach(
            (lease, value) -> {
              try {
                reservations.add(parse(prefix, UUID.fromString(lease), value));
              } catch (IllegalArgumentException
                  | IllegalStateException
                  | DateTimeException malformed) {
                // Se sigue escaneando; las entradas mal formadas solo generan alertas, nunca son
                // candidatas a borrado.
                invalid.add(Hashing.sha256Hex(lease));
              }
            });
    List<String> now = redis.time();
    Instant observed =
        Instant.ofEpochSecond(Long.parseLong(now.get(0)), Long.parseLong(now.get(1)) * 1000);
    return new Inventory(page.getCursor(), redis.hlen(key), observed, reservations, invalid);
  }

  /**
   * Verifica la prueba externa, registra la intención en el journal y luego compara de forma
   * atómica los tres valores almacenados. Una reserva cambiada o parcial nunca se elimina. Repetir
   * una eliminación confirmada es idempotente. Una prueba, journal o Redis no disponible conserva
   * la incertidumbre en lugar de inventar un éxito.
   */
  public Stage reconcile(VaultScope scope, Reservation reservation, UUID evidence) {
    Objects.requireNonNull(evidence);
    String prefix = BudgetKeys.prefix(namespace, scope);
    // Se vuelve a parsear el valor codificado: quienes llaman no pueden fabricar a partir de él un
    // objetivo de prueba distinto.
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

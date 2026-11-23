package pe.axiz.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Verifica la evidencia recibida con independencia de que un checkpoint confiable establezca la
 * cobertura.
 */
public final class AuditChainVerifier {
  /**
   * Defecto más grave encontrado en la evidencia recibida, independiente de la cobertura de
   * entrega.
   */
  public enum IntegrityStatus {
    INTACT,
    GAP,
    INVALID_HASH,
    CONFLICT
  }

  /** Cobertura establecida solo por comparación con un checkpoint confiable. */
  public enum Completeness {
    NOT_ASSESSED,
    COMPLETE_UP_TO,
    TAIL_MISSING
  }

  /** Intervalo afectado inclusivo; para un error de hash o un conflicto, from es igual a to. */
  public record Finding(IntegrityStatus status, long from, long to) {}

  /**
   * upTo es la última secuencia verificada de forma continua, no necesariamente la última recibida.
   */
  public record Result(
      IntegrityStatus integrity,
      long upTo,
      long receivedThrough,
      List<Finding> findings,
      Completeness completeness,
      @Nullable ChainCheckpoint checkpoint) {
    public Result {
      findings = List.copyOf(findings);
    }
  }

  private AuditChainVerifier() {}

  /** La clave se copia y nunca se modifica; un checkpoint null siempre da NOT_ASSESSED. */
  public static Result verify(
      Iterable<AuditEvent> eventsOfOneChain,
      byte @Nullable [] key,
      @Nullable ChainCheckpoint checkpoint) {
    byte @Nullable [] copy = key == null ? null : key.clone();
    try {
      return verifyOwned(eventsOfOneChain, copy, checkpoint);
    } finally {
      if (copy != null) Arrays.fill(copy, (byte) 0);
    }
  }

  private static Result verifyOwned(
      Iterable<AuditEvent> events, byte @Nullable [] key, @Nullable ChainCheckpoint checkpoint) {
    var ordered = new TreeMap<Long, AuditEvent>();
    Set<Finding> findings = new LinkedHashSet<>();
    String chain = checkpoint == null ? null : checkpoint.chainId();
    int received = 0;
    for (AuditEvent event : events) {
      if (++received > 1_000_000)
        throw new IllegalArgumentException("Audit verification batch too large");
      AuditIntegrity seal =
          Objects.requireNonNull(event.integrity(), "Verification requires sealed events");
      if (chain == null) chain = seal.chainId();
      else if (!chain.equals(seal.chainId()))
        throw new IllegalArgumentException("Expected events of one audit chain");
      AuditEvent duplicate = ordered.putIfAbsent(seal.sequence(), event);
      if (duplicate != null
          && !Objects.requireNonNull(duplicate.integrity()).hash().equals(seal.hash()))
        add(findings, IntegrityStatus.CONFLICT, seal.sequence(), seal.sequence());
      // Se verifican incluso los duplicados con el mismo hash: alterar su contenido sin cambiar el
      // hash es un ataque.
      if (!matches(event, seal, key))
        add(findings, IntegrityStatus.INVALID_HASH, seal.sequence(), seal.sequence());
    }
    long firstInvalid = findings.stream().mapToLong(Finding::from).min().orElse(Long.MAX_VALUE);
    long previousSequence = 0;
    String previousHash = SealPreimage.GENESIS;
    long upTo = 0;
    boolean continuous = true;
    for (var entry : ordered.entrySet()) {
      long sequence = entry.getKey();
      AuditIntegrity seal = Objects.requireNonNull(entry.getValue().integrity());
      if (sequence != previousSequence + 1) {
        add(findings, IntegrityStatus.GAP, previousSequence + 1, sequence - 1);
        continuous = false;
      } else if (!equal(previousHash, seal.previousHash())) {
        add(findings, IntegrityStatus.INVALID_HASH, sequence, sequence);
        continuous = false;
      }
      if (firstInvalid <= sequence) continuous = false;
      if (continuous) upTo = sequence;
      previousSequence = sequence;
      previousHash = seal.hash();
    }

    Completeness completeness = Completeness.NOT_ASSESSED;
    if (checkpoint != null) {
      if (previousSequence < checkpoint.sequence()) {
        completeness = Completeness.TAIL_MISSING;
      } else {
        AuditEvent at = ordered.get(checkpoint.sequence());
        String hash =
            checkpoint.sequence() == 0
                ? SealPreimage.GENESIS
                : at == null ? null : Objects.requireNonNull(at.integrity()).hash();
        if (hash != null && !equal(checkpoint.hash(), hash)) {
          add(findings, IntegrityStatus.INVALID_HASH, checkpoint.sequence(), checkpoint.sequence());
          upTo = Math.min(upTo, Math.max(0, checkpoint.sequence() - 1));
        } else if (hash != null
            && findings.stream().noneMatch(f -> f.from() <= checkpoint.sequence())) {
          completeness = Completeness.COMPLETE_UP_TO;
        }
      }
    }
    var sorted = new ArrayList<>(findings);
    sorted.sort(Comparator.comparingLong(Finding::from).thenComparing(Finding::status));
    IntegrityStatus status =
        findings.stream()
            .map(Finding::status)
            .max(Comparator.naturalOrder())
            .orElse(IntegrityStatus.INTACT);
    return new Result(status, upTo, previousSequence, sorted, completeness, checkpoint);
  }

  private static void add(Set<Finding> findings, IntegrityStatus status, long from, long to) {
    var finding = new Finding(status, from, to);
    findings.add(finding);
  }

  private static boolean matches(AuditEvent event, AuditIntegrity seal, byte @Nullable [] key) {
    try {
      return equal(SealPreimage.hash(event, seal, key), seal.hash());
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static boolean equal(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}

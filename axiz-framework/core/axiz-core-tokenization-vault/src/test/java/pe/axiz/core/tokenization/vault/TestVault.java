package pe.axiz.core.tokenization.vault;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;

/** Modelo atómico solo para pruebas. No se entrega ninguna bóveda en memoria para producción. */
class TestVault implements TokenVault {
  FingerprintKeyState state = new FingerprintKeyState("main", 1, "old", List.of("old"));
  final Map<Token, VaultRecord> records = new HashMap<>();
  final Map<Reference, Token> references = new HashMap<>();
  final AtomicInteger reads = new AtomicInteger();
  final AtomicInteger writes = new AtomicInteger();
  boolean lostAck;
  boolean staleOnce;
  boolean constantCollision;
  boolean orphan;

  @Override
  public synchronized FingerprintKeyState keyState(String keysetId) {
    reads.incrementAndGet();
    return state;
  }

  @Override
  public synchronized Optional<VaultRecord> findByFingerprint(
      VaultScope scope, List<KeyedHash> candidates) {
    reads.incrementAndGet();
    if (orphan) throw new VaultIntegrityException();
    for (KeyedHash hash : candidates) {
      Token token = references.get(new Reference(scope, hash));
      if (token != null) return Optional.ofNullable(records.get(token));
    }
    return Optional.empty();
  }

  @Override
  public synchronized PutOutcome putIfAbsent(
      VaultRecord record, FingerprintKeyState expected, List<KeyedHash> others) {
    writes.incrementAndGet();
    if (staleOnce) {
      staleOnce = false;
      state = new FingerprintKeyState("main", 2, "new", List.of("new", "old"));
    }
    if (!state.equals(expected)) return new PutOutcome.StaleKeyState();
    if (constantCollision) return new PutOutcome.TokenCollision();
    for (KeyedHash hash : others) {
      Token winner = references.get(new Reference(record.scope(), hash));
      if (winner != null) return new PutOutcome.FingerprintExists(winner);
    }
    for (KeyedHash hash : record.fingerprints()) {
      Token winner = references.get(new Reference(record.scope(), hash));
      if (winner != null) return new PutOutcome.FingerprintExists(winner);
    }
    if (records.containsKey(record.token())) return new PutOutcome.TokenCollision();
    records.put(record.token(), record);
    for (KeyedHash hash : record.fingerprints())
      references.put(new Reference(record.scope(), hash), record.token());
    if (lostAck) {
      lostAck = false;
      throw new VaultUncertainException();
    }
    return new PutOutcome.Created();
  }

  @Override
  public synchronized Optional<VaultRecord> find(VaultScope scope, Token token) {
    reads.incrementAndGet();
    VaultRecord record = records.get(token);
    return record == null || !record.scope().equals(scope) ? Optional.empty() : Optional.of(record);
  }

  @Override
  public synchronized DeleteOutcome delete(VaultRecord expected) {
    writes.incrementAndGet();
    VaultRecord actual = records.get(expected.token());
    if (actual == null) return DeleteOutcome.NOT_FOUND;
    if (actual.revision() != expected.revision() || !actual.scope().equals(expected.scope()))
      return DeleteOutcome.CHANGED;
    for (KeyedHash hash : actual.fingerprints())
      if (!actual.token().equals(references.get(new Reference(actual.scope(), hash))))
        return DeleteOutcome.INTEGRITY_FAILURE;
    records.remove(actual.token());
    for (KeyedHash hash : actual.fingerprints())
      references.remove(new Reference(actual.scope(), hash));
    return DeleteOutcome.DELETED;
  }

  record Reference(VaultScope scope, KeyedHash hash) {}
}

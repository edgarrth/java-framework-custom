package pe.axiz.adapter.tokenization.dynamodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.vault.DeleteOutcome;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.PutOutcome;
import pe.axiz.core.tokenization.vault.TokenVault;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultScope;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Borrowed synchronous DynamoDB client; the operator provisions schema-v1 and key control items.
 */
public final class DynamoDbTokenVault implements TokenVault {
  private final DynamoDbClient client;
  private final VaultRequests requests;

  public DynamoDbTokenVault(DynamoDbClient client, String table, Duration requestTimeout) {
    this.client = Objects.requireNonNull(client);
    requests = new VaultRequests(table, requestTimeout);
  }

  @Override
  public FingerprintKeyState keyState(String keysetId) {
    var request = requests.get(VaultItems.controlKey(keysetId));
    try {
      return VaultItems.control(keysetId, client.getItem(request).item());
    } catch (RuntimeException failure) {
      throw VaultRequests.readFailure(failure);
    }
  }

  @Override
  public Optional<VaultRecord> find(VaultScope scope, Token token) {
    try {
      var item = client.getItem(requests.get(VaultItems.tokenKey(token))).item();
      if (item.isEmpty()) return Optional.empty();
      VaultRecord record = VaultItems.record(item);
      if (!record.token().equals(token)) throw new VaultIntegrityException();
      return record.scope().equals(scope) ? Optional.of(record) : Optional.empty();
    } catch (RuntimeException failure) {
      throw VaultRequests.readFailure(failure);
    }
  }

  @Override
  public Optional<VaultRecord> findByFingerprint(VaultScope scope, List<KeyedHash> candidates) {
    var request = requests.references(scope, candidates, null);
    try {
      for (int attempt = 0; attempt < VaultRequests.ATTEMPTS; attempt++) {
        var initial = client.transactGetItems(request).responses();
        Optional<Token> owner = requests.owner(scope, candidates, initial, false);
        if (owner.isEmpty()) return Optional.empty();
        // Re-read indexes AND token together so a concurrent delete is not mistaken for an orphan.
        var snapshot =
            client
                .transactGetItems(requests.references(scope, candidates, owner.get()))
                .responses();
        Optional<Token> confirmed = requests.owner(scope, candidates, snapshot, true);
        if (confirmed.isEmpty()) return Optional.empty();
        if (owner.equals(confirmed))
          return Optional.of(requests.association(scope, candidates, owner.get(), snapshot));
      }
      throw VaultRequests.unavailable();
    } catch (RuntimeException failure) {
      throw VaultRequests.readFailure(failure);
    }
  }

  @Override
  public PutOutcome putIfAbsent(
      VaultRecord record, FingerprintKeyState state, List<KeyedHash> otherReadFingerprints) {
    var request = requests.insert(record, state, otherReadFingerprints);
    for (int attempt = 0; attempt < VaultRequests.ATTEMPTS; attempt++) {
      try {
        client.transactWriteItems(request);
        return new PutOutcome.Created();
      } catch (TransactionCanceledException rejected) {
        var reasons = VaultRequests.reasons(rejected, request.transactItems().size());
        if (VaultRequests.condition(reasons, 0)
            || VaultRequests.condition(reasons, reasons.size() - 1))
          return new PutOutcome.StaleKeyState();
        boolean fingerprint = false;
        for (int i = 2; i < reasons.size() - 1; i++)
          fingerprint |= VaultRequests.condition(reasons, i);
        if (fingerprint) {
          var candidates = new ArrayList<>(record.fingerprints());
          candidates.addAll(otherReadFingerprints);
          Optional<VaultRecord> winner = findByFingerprint(record.scope(), candidates);
          if (winner.isPresent()) return new PutOutcome.FingerprintExists(winner.get().token());
          // The competing association was deleted after confirmed rollback; retry the same plan.
        } else if (VaultRequests.condition(reasons, 1)) return new PutOutcome.TokenCollision();
        else if (!VaultRequests.conflict(reasons)) throw VaultRequests.unavailable();
      } catch (RuntimeException failure) {
        throw VaultRequests.writeFailure(failure);
      }
    }
    throw VaultRequests.unavailable();
  }

  @Override
  public DeleteOutcome delete(VaultRecord expected) {
    var request = requests.delete(expected);
    for (int attempt = 0; attempt < VaultRequests.ATTEMPTS; attempt++) {
      try {
        client.transactWriteItems(request);
        return DeleteOutcome.DELETED;
      } catch (TransactionCanceledException rejected) {
        var reasons = VaultRequests.reasons(rejected, request.transactItems().size());
        if (VaultRequests.condition(reasons, 0))
          return find(expected.scope(), expected.token()).isEmpty()
              ? DeleteOutcome.NOT_FOUND
              : DeleteOutcome.CHANGED;
        for (int i = 1; i < reasons.size(); i++)
          if (VaultRequests.condition(reasons, i)) return DeleteOutcome.INTEGRITY_FAILURE;
        if (!VaultRequests.conflict(reasons)) throw VaultRequests.unavailable();
      } catch (RuntimeException failure) {
        throw VaultRequests.writeFailure(failure);
      }
    }
    throw VaultRequests.unavailable();
  }
}

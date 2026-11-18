package pe.axiz.adapter.tokenization.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.vault.DeleteOutcome;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.MigrationOutcome;
import pe.axiz.core.tokenization.vault.PutOutcome;
import pe.axiz.core.tokenization.vault.TokenVaultMaintenance;
import pe.axiz.core.tokenization.vault.VaultKeyTransition;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultReferencePage;
import pe.axiz.core.tokenization.vault.VaultScanPage;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import pe.axiz.core.tokenization.vault.WrappingKeyTransition;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Explicit privileged adapter for an independently authorized maintenance job and borrowed client.
 */
public final class DynamoDbVaultMaintenance implements TokenVaultMaintenance {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The SDK client is an injected collaborator with shared identity and lifecycle; it cannot be copied.")
  private final DynamoDbClient client;

  private final DynamoDbTokenVault operational;
  private final MaintenanceRequests requests;

  public DynamoDbVaultMaintenance(DynamoDbClient client, String table, Duration timeout) {
    this.client = Objects.requireNonNull(client);
    operational = new DynamoDbTokenVault(client, table, timeout);
    requests = new MaintenanceRequests(new VaultRequests(table, timeout));
  }

  @Override
  public WrappingKeyState wrappingState(String keyset) {
    try {
      return VaultItems.wrapping(keyset, client.getItem(requests.wrappingGet(keyset)).item());
    } catch (RuntimeException failure) {
      throw VaultRequests.readFailure(failure);
    }
  }

  @Override
  public boolean initializeWrapping(WrappingKeyState initial) {
    return control(requests.initializeWrapping(initial));
  }

  @Override
  public boolean transitionWrapping(WrappingKeyTransition transition) {
    return control(requests.transitionWrapping(transition));
  }

  @Override
  public boolean initialize(FingerprintKeyState initial) {
    return control(requests.initialize(initial));
  }

  @Override
  public boolean transition(VaultKeyTransition transition) {
    return control(requests.transition(transition));
  }

  private boolean control(TransactWriteItemsRequest request) {
    for (int attempt = 0; attempt < VaultRequests.ATTEMPTS; attempt++) {
      try {
        client.transactWriteItems(request);
        return true;
      } catch (TransactionCanceledException rejected) {
        var reasons = VaultRequests.reasons(rejected, request.transactItems().size());
        for (int i = 0; i < reasons.size(); i++)
          if (VaultRequests.condition(reasons, i)) return false;
        if (!VaultRequests.conflict(reasons)) throw VaultRequests.unavailable();
      } catch (RuntimeException failure) {
        throw VaultRequests.writeFailure(failure);
      }
    }
    throw VaultRequests.unavailable();
  }

  @Override
  public VaultScanPage scan(String keysetId, @Nullable String cursor, int limit) {
    var request = requests.scan(keysetId, null, cursor, limit);
    try {
      var response = client.scan(request);
      return new VaultScanPage(
          response.items().stream().map(VaultItems::record).toList(),
          requests.cursor(keysetId, response.lastEvaluatedKey()));
    } catch (RuntimeException failure) {
      throw VaultRequests.readFailure(failure);
    }
  }

  @Override
  public VaultReferencePage references(
      String keysetId, String keyId, @Nullable String cursor, int limit) {
    var request = requests.scan(keysetId, keyId, cursor, limit);
    try {
      var response = client.scan(request);
      return new VaultReferencePage(
          response.items().size(), requests.cursor(keysetId, response.lastEvaluatedKey()));
    } catch (RuntimeException failure) {
      throw VaultRequests.readFailure(failure);
    }
  }

  @Override
  public MigrationOutcome replace(
      VaultRecord expected, VaultRecord replacement, FingerprintKeyState state) {
    var request = requests.replace(expected, replacement, state);
    for (int attempt = 0; attempt < VaultRequests.ATTEMPTS; attempt++) {
      try {
        client.transactWriteItems(request);
        return MigrationOutcome.MIGRATED;
      } catch (TransactionCanceledException rejected) {
        var reasons = VaultRequests.reasons(rejected, request.transactItems().size());
        if (VaultRequests.condition(reasons, 0)
            || VaultRequests.condition(reasons, reasons.size() - 1))
          return MigrationOutcome.STALE_KEY_STATE;
        if (VaultRequests.condition(reasons, 1)) return MigrationOutcome.CHANGED;
        for (int i = 2; i < reasons.size() - 1; i++)
          if (VaultRequests.condition(reasons, i)) return MigrationOutcome.INTEGRITY_FAILURE;
        if (!VaultRequests.conflict(reasons)) throw VaultRequests.unavailable();
      } catch (RuntimeException failure) {
        throw VaultRequests.writeFailure(failure);
      }
    }
    throw VaultRequests.unavailable();
  }

  @Override
  public FingerprintKeyState keyState(String keysetId) {
    return operational.keyState(keysetId);
  }

  @Override
  public Optional<VaultRecord> find(VaultScope scope, Token token) {
    return operational.find(scope, token);
  }

  @Override
  public Optional<VaultRecord> findByFingerprint(VaultScope scope, List<KeyedHash> candidates) {
    return operational.findByFingerprint(scope, candidates);
  }

  @Override
  public PutOutcome putIfAbsent(
      VaultRecord record, FingerprintKeyState state, List<KeyedHash> others) {
    return operational.putIfAbsent(record, state, others);
  }

  @Override
  public DeleteOutcome delete(VaultRecord expected) {
    return operational.delete(expected);
  }
}

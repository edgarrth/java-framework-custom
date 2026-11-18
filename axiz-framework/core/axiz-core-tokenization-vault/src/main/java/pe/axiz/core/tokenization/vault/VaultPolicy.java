package pe.axiz.core.tokenization.vault;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.sensitive.PanDetector;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenMetadata;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;

/**
 * Shared CPU-only validation, access checks, index planning and record invariants for both engines.
 */
public final class VaultPolicy {
  static final String ENCRYPTION_CONTEXT = "purpose:token-vault";
  private final Map<TokenType, Registered> types;
  private final TokenAccessPolicy access;
  private final BlindIndex indexes;
  private final String authorityPrefix;
  private final Clock clock;

  public VaultPolicy(
      List<TokenTypePolicy> policies,
      TokenAccessPolicy access,
      BlindIndex indexes,
      String authorityPrefix,
      Clock clock) {
    if (policies.isEmpty() || policies.size() > 64) throw TokenErrors.failure(TokenErrors.INVALID);
    this.access = Objects.requireNonNull(access);
    this.indexes = Objects.requireNonNull(indexes);
    this.clock = Objects.requireNonNull(clock);
    this.authorityPrefix = VaultIdentifiers.identifier(authorityPrefix, 64);
    Map<TokenType, Registered> registered = new HashMap<>();
    for (TokenTypePolicy policy : policies) {
      var profile = policy.profile();
      var panExemption = policy.panException();
      if (panExemption != null && panExemption.profileVersion() != profile.version())
        throw TokenErrors.failure(TokenErrors.INVALID);
      if (registered.putIfAbsent(
              policy.type(),
              new Registered(policy, profile.id(), profile.version(), profile.cardData()))
          != null) throw TokenErrors.failure(TokenErrors.INVALID);
    }
    types = Map.copyOf(registered);
  }

  Clock clock() {
    return clock;
  }

  Request authorize(
      TokenCaller caller,
      TokenOperation operation,
      TokenType type,
      TokenDomain domain,
      UUID operationId,
      @Nullable String purpose) {
    String suffix = operation.name().toLowerCase(Locale.ROOT);
    if (!caller.authorities().contains(authorityPrefix + suffix)
        || !caller.domains().contains(domain)) throw TokenErrors.failure(TokenErrors.DENIED);
    Registered registered = types.get(type);
    if (registered == null) throw TokenErrors.failure(TokenErrors.INVALID);
    TokenTypePolicy policy = registered.policy();
    if (!policy.domains().contains(domain)) throw TokenErrors.failure(TokenErrors.DENIED);
    if (operation == TokenOperation.LOOKUP || operation == TokenOperation.DETOKENIZE) {
      if (purpose == null || !policy.purposes().contains(purpose))
        throw TokenErrors.failure(TokenErrors.DENIED);
    } else if (purpose != null) throw TokenErrors.failure(TokenErrors.INVALID);
    access.check(caller, operation, type, domain, purpose);
    return new Request(
        caller,
        operation,
        policy,
        new VaultScope(type, caller.tenant(), domain, registered.profileId(), registered.version()),
        operationId,
        purpose,
        registered.cardData());
  }

  SensitiveValue canonicalize(Request request, SensitiveValue input) {
    TokenTypePolicy policy = request.policy();
    if (!policy.profile().id().equals(request.scope().profileId())
        || policy.profile().version() != request.scope().canonicalVersion()
        || policy.profile().cardData() != request.cardData())
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
    boolean guard = !request.cardData() && policy.panException() == null;
    if (guard && PanDetector.containsPan(input.reveal()))
      throw TokenErrors.failure(TokenErrors.INVALID);
    SensitiveValue canonical;
    try {
      canonical = Objects.requireNonNull(policy.profile().canonicalize(input));
    } catch (RuntimeException invalid) {
      throw TokenErrors.failure(TokenErrors.INVALID);
    }
    if (canonical == input) throw TokenErrors.failure(TokenErrors.INVALID);
    try {
      if (guard && PanDetector.containsPan(canonical.reveal()))
        throw TokenErrors.failure(TokenErrors.INVALID);
      return canonical;
    } catch (RuntimeException failure) {
      canonical.close();
      throw failure;
    }
  }

  Fingerprints fingerprints(Request request, SensitiveValue canonical, FingerprintKeyState state) {
    if (!state.keysetId().equals(request.policy().keysetId())) throw new VaultIntegrityException();
    if (!indexes.keyIds().containsAll(state.readKeyIds()))
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
    String value = canonical.reveal();
    KeyedHash write = indexes.index(value, request.scope().indexScope(), state.writeKeyId());
    List<KeyedHash> all = new ArrayList<>();
    all.add(write);
    for (String key : state.readKeyIds())
      if (!key.equals(state.writeKeyId()))
        all.add(indexes.index(value, request.scope().indexScope(), key));
    return new Fingerprints(write, List.copyOf(all));
  }

  Map<String, String> metadata(Request request, SensitiveValue canonical) {
    Map<String, String> result =
        TokenMetadata.snapshot(request.policy().profile().metadata(canonical));
    if (!request.policy().metadataKeys().containsAll(result.keySet()))
      throw new VaultIntegrityException();
    return result;
  }

  VaultRecord record(
      Request request,
      Token token,
      KeyedHash write,
      byte[] encrypted,
      Map<String, String> metadata) {
    var now = clock.instant();
    return VaultRecord.builder()
        .token(token)
        .scope(request.scope())
        .keysetId(request.policy().keysetId())
        .fingerprints(List.of(write))
        .revision(1)
        .ciphertext(encrypted)
        .createdAt(now)
        .expiresAt(now.plus(request.policy().retention()))
        .metadata(metadata)
        .build();
  }

  VaultRecord checked(Request request, VaultRecord record, @Nullable List<KeyedHash> candidates) {
    if (!record.scope().equals(request.scope())
        || !record.keysetId().equals(request.policy().keysetId())
        || !request.policy().metadataKeys().containsAll(record.metadata().keySet())
        || (candidates != null && record.fingerprints().stream().noneMatch(candidates::contains)))
      throw new VaultIntegrityException();
    return record;
  }

  boolean expired(VaultRecord record) {
    return !record.expiresAt().isAfter(clock.instant());
  }

  TokenAuditEntry audit(Request request, TokenAuditEntry.Result outcome, @Nullable Token token) {
    return TokenAuditEntry.of(
        request.operation(),
        request.caller(),
        request.scope().type(),
        request.scope().domain(),
        request.operationId(),
        request.purpose(),
        outcome,
        token,
        clock.instant());
  }

  static BusinessException safeFailure(RuntimeException error) {
    return TokenErrors.sanitize(error);
  }

  static TokenAuditEntry.Result failureResult(RuntimeException error) {
    if (error instanceof VaultIntegrityException) return TokenAuditEntry.Result.INTEGRITY_FAILURE;
    return switch (safeFailure(error).code().value()) {
      case "AXZ-TOKEN-0400" -> TokenAuditEntry.Result.INVALID;
      case "AXZ-TOKEN-0403" -> TokenAuditEntry.Result.DENIED;
      case "AXZ-TOKEN-0404" -> TokenAuditEntry.Result.ABSENT;
      case "AXZ-TOKEN-0429" -> TokenAuditEntry.Result.RATE_LIMITED;
      case "AXZ-TOKEN-0504" -> TokenAuditEntry.Result.UNCERTAIN;
      default -> TokenAuditEntry.Result.UNAVAILABLE;
    };
  }

  record Request(
      TokenCaller caller,
      TokenOperation operation,
      TokenTypePolicy policy,
      VaultScope scope,
      UUID operationId,
      @Nullable String purpose,
      boolean cardData) {}

  record Fingerprints(KeyedHash write, List<KeyedHash> all) {
    List<KeyedHash> others() {
      return all.subList(1, all.size());
    }
  }

  private record Registered(
      TokenTypePolicy policy, String profileId, int version, boolean cardData) {}
}

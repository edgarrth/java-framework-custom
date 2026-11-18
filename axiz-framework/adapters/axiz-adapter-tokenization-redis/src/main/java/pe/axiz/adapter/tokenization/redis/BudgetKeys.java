package pe.axiz.adapter.tokenization.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import pe.axiz.core.crypto.Hashing;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Limits;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Windows;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultScope;

/** Server identities only; no source value, fingerprint, token, purpose or request identifier. */
final class BudgetKeys {
  record Counter(String key, long limit, Duration window) {}

  private final List<String> dimensions;

  BudgetKeys(String namespace, TokenCaller caller, VaultScope scope) {
    if (!caller.tenant().equals(scope.tenant())) throw TokenErrors.failure(TokenErrors.DENIED);
    String tag = hash(namespace, scope.tenant(), scope.type().value(), scope.domain().value());
    String prefix = "axiz:token-budget:{" + tag + "}:";
    dimensions =
        List.of(
            prefix + "subject:" + hash(caller.subject()),
            prefix + "client:" + hash(caller.clientId()),
            prefix + "tenant");
  }

  List<String> leases() {
    return dimensions.stream().map(key -> key + ":leases").toList();
  }

  List<Counter> counters(TokenBudgetPolicy policy, TokenOperation operation) {
    return switch (operation) {
      case TOKENIZE, LOOKUP -> counters("query", policy.query());
      case DETOKENIZE -> counters("reveal", policy.reveal());
      case DESCRIBE, DELETE -> counters("metadata", policy.metadata());
    };
  }

  List<Counter> writes(TokenBudgetPolicy policy) {
    return counters("write", policy.writes());
  }

  private List<Counter> counters(String group, Limits limits) {
    List<Counter> counters = new ArrayList<>(9);
    List<Windows> windows = List.of(limits.subject(), limits.client(), limits.tenant());
    for (int i = 0; i < dimensions.size(); i++) {
      String key = dimensions.get(i) + ":" + group;
      Windows window = windows.get(i);
      counters.add(new Counter(key + ":burst", window.burst(), Duration.ofSeconds(1)));
      counters.add(new Counter(key + ":minute", window.minute(), Duration.ofMinutes(1)));
      counters.add(new Counter(key + ":day", window.day(), Duration.ofDays(1)));
    }
    return List.copyOf(counters);
  }

  private static String hash(String... parts) {
    StringBuilder framed = new StringBuilder("v1:");
    for (String part : parts) framed.append(part.length()).append(':').append(part);
    return Hashing.sha256Hex(framed.toString());
  }
}

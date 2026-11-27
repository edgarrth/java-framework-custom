package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TextTokenProfiles;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.TokenType;

class VaultPolicyContractTest {
  @Test
  void applicationAccessPolicyCanDenyEveryOperationEvenWithAllScopesPresent() {
    var fixture = new VaultTokenizerTest();
    try (SensitiveValue value = SensitiveValue.of("00883344")) {
      var result = fixture.tokenize("00883344", VaultTokenizerTest.DNI, "tenant");
      var seen = new ArrayList<TokenOperation>();
      var policy =
          new VaultPolicy(
              List.of(VaultTokenizerTest.policy(VaultTokenizerTest.DNI, TextTokenProfiles.dniPe())),
              (caller, operation, type, domain, purpose) -> {
                seen.add(operation);
                throw TokenErrors.failure(TokenErrors.DENIED);
              },
              fixture.index,
              "SCOPE_tokens:",
              VaultTokenizerTest.CLOCK);
      var engine = fixture.engine(policy);
      var caller = VaultTokenizerTest.caller("tenant");
      List<Runnable> denied =
          List.of(
              () ->
                  engine.tokenize(
                      caller,
                      VaultTokenizerTest.DNI,
                      value,
                      VaultTokenizerTest.DOMAIN,
                      UUID.randomUUID().toString()),
              () ->
                  engine.lookup(
                      caller,
                      VaultTokenizerTest.DNI,
                      value,
                      VaultTokenizerTest.DOMAIN,
                      "customer-support"),
              () ->
                  engine.detokenize(
                      caller,
                      VaultTokenizerTest.DNI,
                      result.token(),
                      VaultTokenizerTest.DOMAIN,
                      "customer-support"),
              () ->
                  engine.describe(
                      caller, VaultTokenizerTest.DNI, result.token(), VaultTokenizerTest.DOMAIN),
              () ->
                  engine.delete(
                      caller, VaultTokenizerTest.DNI, result.token(), VaultTokenizerTest.DOMAIN));
      for (Runnable operation : denied) VaultTokenizerTest.code("AXZ-TOKEN-0403", operation);
      assertEquals(
          Set.of(
              TokenOperation.TOKENIZE,
              TokenOperation.LOOKUP,
              TokenOperation.DETOKENIZE,
              TokenOperation.DESCRIBE,
              TokenOperation.DELETE),
          Set.copyOf(seen));
      assertEquals(5, seen.size());
      assertTrue(
          fixture
              .engine
              .describe(caller, VaultTokenizerTest.DNI, result.token(), VaultTokenizerTest.DOMAIN)
              .isPresent());
      assertEquals(
          5,
          fixture.events.stream().filter(e -> e.result() == TokenAuditEntry.Result.DENIED).count());
    } finally {
      fixture.close();
    }
  }

  private static final class Profile implements TokenProfile {
    boolean cardData;
    SensitiveValue lastCanonical;
    String output = "00883344";

    @Override
    public String id() {
      return "test.profile";
    }

    @Override
    public int version() {
      return 1;
    }

    @Override
    public boolean cardData() {
      return cardData;
    }

    @Override
    public SensitiveValue canonicalize(SensitiveValue input) {
      lastCanonical = SensitiveValue.of(output);
      return lastCanonical;
    }

    @Override
    public Map<String, String> metadata(SensitiveValue canonical) {
      return Map.of("label", "synthetic");
    }
  }

  private static TokenTypePolicy.Builder registered(TokenType type, Profile profile) {
    return TokenTypePolicy.builder()
        .type(type)
        .profile(profile)
        .keysetId("main")
        .domains(Set.of(VaultTokenizerTest.DOMAIN))
        .purposes(Set.of("customer-support"))
        .retention(Duration.ofDays(1))
        .metadataKeys(Set.of("label"));
  }

  @Test
  void registeredProfilesCannotChangeCardDataClassificationAndPanRejectionsEraseTheirOutput() {
    var fixture = new VaultTokenizerTest();
    var profile = new Profile();
    try (SensitiveValue input = SensitiveValue.of("00883344")) {
      var policy =
          new VaultPolicy(
              List.of(registered(VaultTokenizerTest.DNI, profile).build()),
              (caller, operation, type, domain, purpose) -> {},
              fixture.index,
              "SCOPE_tokens:",
              VaultTokenizerTest.CLOCK);
      var caller = VaultTokenizerTest.caller("tenant");
      var request =
          policy.authorize(
              caller,
              TokenOperation.TOKENIZE,
              VaultTokenizerTest.DNI,
              VaultTokenizerTest.DOMAIN,
              UUID.randomUUID(),
              null);
      try (SensitiveValue canonical = policy.canonicalize(request, input)) {
        assertEquals(Map.of("label", "synthetic"), policy.metadata(request, canonical));
        var state = new FingerprintKeyState("main", 1, "old", List.of("old", "new"));
        var fingerprints = policy.fingerprints(request, canonical, state);
        assertEquals("old", fingerprints.write().keyId());
        assertEquals(1, fingerprints.others().size());
        assertEquals("new", fingerprints.others().getFirst().keyId());
      }
      profile.output = "4111111111111111";
      assertThrows(AxizException.class, () -> policy.canonicalize(request, input));
      assertThrows(IllegalStateException.class, profile.lastCanonical::reveal);
      profile.cardData = true;
      VaultTokenizerTest.code("AXZ-TOKEN-0503", () -> policy.canonicalize(request, input));
    } finally {
      fixture.close();
    }
  }

  @Test
  void reviewedPanHeuristicExemptionsAreBoundToTheRegisteredProfileVersion() {
    var fixture = new VaultTokenizerTest();
    var profile = new Profile();
    try {
      var exception = new TokenTypePolicy.PanHeuristicExemption("national-id", "review-123", 1);
      var accepted = registered(VaultTokenizerTest.DNI, profile).panException(exception).build();
      assertEquals(exception, accepted.panException());
      var policies = new ArrayList<TokenTypePolicy>();
      for (int i = 0; i < 64; i++)
        policies.add(registered(new TokenType("ID_" + i), profile).build());
      new VaultPolicy(
          policies,
          (caller, operation, type, domain, purpose) -> {},
          fixture.index,
          "SCOPE_tokens:",
          VaultTokenizerTest.CLOCK);
      policies.add(registered(new TokenType("ID_64"), profile).build());
      assertThrows(
          AxizException.class,
          () ->
              new VaultPolicy(
                  policies,
                  (caller, operation, type, domain, purpose) -> {},
                  fixture.index,
                  "SCOPE_tokens:",
                  VaultTokenizerTest.CLOCK));
      new VaultPolicy(
          List.of(accepted),
          (caller, operation, type, domain, purpose) -> {},
          fixture.index,
          "SCOPE_tokens:",
          VaultTokenizerTest.CLOCK);
      var mismatched =
          registered(VaultTokenizerTest.DNI, profile)
              .panException(
                  new TokenTypePolicy.PanHeuristicExemption("national-id", "review-123", 2))
              .build();
      assertThrows(
          AxizException.class,
          () ->
              new VaultPolicy(
                  List.of(mismatched),
                  (caller, operation, type, domain, purpose) -> {},
                  fixture.index,
                  "SCOPE_tokens:",
                  VaultTokenizerTest.CLOCK));
      profile.cardData = true;
      assertThrows(
          AxizException.class,
          () -> registered(VaultTokenizerTest.DNI, profile).panException(exception).build());
      assertFalse(registered(VaultTokenizerTest.DNI, profile).build().panException() != null);
    } finally {
      fixture.close();
    }
  }
}
